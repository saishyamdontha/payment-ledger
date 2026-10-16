# Payment Ledger Service

A double-entry payment ledger in **Java 21 + Spring Boot + PostgreSQL**, built to answer one question:
**does money stay correct when the system crashes in the middle of a payment?**

## Results

Verified by an automated chaos test that `kill -9`s the service and hard-kills the database under load:

| Run | Transfers | Crashes (app / DB) | Lost | Duplicated | Double charges prevented by retry |
|-----|-----------|--------------------|------|------------|-----------------------------------|
| 1   | 3,229     | 14 / 6             | 0    | 0          | 9                                 |
| 2   | 3,142     | 13 / 7             | 0    | 0          | 3                                 |
| **Total** | **6,371** | **27 / 13 = 40** | **0** | **0** | **12** |

All 10 invariant checks passed in every run (see [What the chaos test checks](#what-the-chaos-test-checks)).

Concurrency test: **100 simultaneous transfers against a balance of 50 → exactly 50 accepted, 50 rejected, no overspend.**
Idempotency test: **50 concurrent requests with the same key → 1 transfer created, 49 replayed.**

## Architecture

```mermaid
flowchart LR
    C[Client] -- "POST /transfers<br/>Idempotency-Key" --> API[REST API<br/>Spring Boot]
    API --> S[LedgerService<br/>one DB transaction]
    S --> L1[1. Lock both accounts<br/>in id order]
    L1 --> L2[2. Key seen before?<br/>replay original]
    L2 --> L3[3. Debit + credit]
    L3 --> L4[4. Write transfer +<br/>2 ledger entries + key]
    L4 --> DB[(PostgreSQL)]
```

## Design decisions

| Problem | Solution | Why |
|---|---|---|
| Money must never appear or vanish | **Double-entry**: every transfer writes a debit and a credit entry that sum to 0. Deposits come from a reserve account. | The total of all balances is constant, so correctness becomes checkable |
| Two transfers spending the same balance | **Row-level locks** (`SELECT ... FOR UPDATE`) inside one transaction | The second transfer waits and then sees the updated balance |
| Deadlocks (A→B and B→A at once) | Always lock the **lower account id first** | Both transactions lock in the same order, so neither waits on the other |
| Client retries after a timeout | **Idempotency-Key** required on every payment; same key returns the original transfer (`200`, `Idempotent-Replayed: true`) | A retry after a lost response never charges twice |
| Two duplicates racing | Key stored in the **same transaction** as the transfer, with a PRIMARY KEY constraint | The database allows only one; the loser rolls back entirely |
| Same key, different amount | Request hash stored with the key → `422` | Prevents accidental key reuse |
| Floating-point errors | Amounts stored as **`BIGINT` in minor units** (paise) | No rounding, ever |
| Crash mid-transfer | All writes in **one transaction** | PostgreSQL rolls back partial work |

## What the chaos test checks

`chaos/chaos_test.py` runs 8 concurrent clients sending transfers. Clients retry unknown outcomes (dropped connections, 5xx) **with the same key**. Meanwhile it randomly `kill -9`s the service or kills the Postgres container, then restarts it. Afterwards it checks:

1. Total money is unchanged
2. Every balance equals the sum of its ledger entries
3. Every transfer has exactly 2 entries summing to 0
4. No lost payments: every success the client saw exists in the database
5. The transfer id the client received matches the stored one
6. No duplicates: each key maps to exactly one transfer
7. Rejected payments (`409`) left nothing behind
8. No phantom transfers without a key
9. Final balances match what the client expects
10. Every request resolved

## Run it

Requires Java 21, Docker and Python 3.

```bash
docker compose up -d                                  # PostgreSQL on port 5433
cd ledger-service && ./mvnw test                      # unit + concurrency + idempotency tests
./mvnw spring-boot:run                                # API on port 8080
```

```bash
curl -X POST localhost:8080/accounts -H 'Content-Type: application/json' -d '{"ownerName":"Sam","currency":"INR"}'
curl -X POST localhost:8080/accounts/2/deposit -H "Idempotency-Key: $(uuidgen)" \
     -H 'Content-Type: application/json' -d '{"amountMinor":100000}'
```

Chaos test (stop any running instance first):
```bash
cd ledger-service && ./mvnw -q package -DskipTests && cd ..
python3 chaos/chaos_test.py 20                        # 20 crashes
```

## API

| Method | Path | Notes |
|---|---|---|
| `POST` | `/accounts` | Open an account |
| `GET` | `/accounts/{id}` | Balance |
| `POST` | `/accounts/{id}/deposit` | Requires `Idempotency-Key` |
| `POST` | `/transfers` | Requires `Idempotency-Key` |
| `GET` | `/accounts/{id}/entries` | Ledger history |

Errors use RFC 7807 problem details: `404` not found, `409` insufficient funds, `422` key reused with a different request, `400` invalid input.

## Limitations

- Single PostgreSQL instance. Disk failure, replication and network partitions are not tested.
- The chaos test only covers the crash points it happened to hit; more runs give more confidence, not proof.
- Every deposit locks the reserve account, which serialises deposits. Correct, but a throughput bottleneck at scale.
- Tests currently use the local Docker database (Testcontainers planned).

## Roadmap

- [ ] Transactional outbox + Kafka, so payment events reach other services even through crashes
- [ ] Reconciliation endpoint that runs the invariant checks on demand
- [ ] Testcontainers integration tests + GitHub Actions CI
- [ ] OpenAPI / Swagger UI
