#!/usr/bin/env python3
"""
Chaos test for the payment ledger.

Clients send transfers (each with an Idempotency-Key) while a chaos thread
kill -9's the app and hard-kills the Postgres container. Afterwards an auditor
checks the database against everything the clients were told.

Usage:  python3 chaos/chaos_test.py [target_crashes]     (default 20)
"""
import itertools
import json
import random
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SERVICE = ROOT / "ledger-service"
BASE = "http://localhost:8080"
RESERVE_TOTAL = 1_000_000_000_000

TARGET_CRASHES = int(sys.argv[1]) if len(sys.argv) > 1 else 20
NUM_ACCOUNTS = 5
INITIAL_DEPOSIT = 10_000
WORKERS = 8
RETRY_DEADLINE_S = 180
RUN_ID = uuid.uuid4().hex[:8]
FINAL_STATUSES = {200, 201, 400, 404, 409, 422}   # anything else = unknown outcome -> retry

# ---------------------------------------------------------------- helpers
def http(method, path, body=None, key=None, timeout=5):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if key:
        req.add_header("Idempotency-Key", key)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        return e.code, None


def send_with_retry(method, path, body, key):
    """Retry with the SAME key until we get a definite answer."""
    deadline = time.time() + RETRY_DEADLINE_S
    attempts = 0
    while time.time() < deadline:
        attempts += 1
        try:
            status, data = http(method, path, body, key)
            if status in FINAL_STATUSES:
                return status, data, attempts
        except Exception:
            pass   # connection refused / reset / timeout = outcome unknown
        time.sleep(min(0.2 * attempts, 2) + random.random() * 0.2)
    return None, None, attempts


def compose(*args):
    subprocess.run(["docker", "compose", *args], cwd=ROOT, check=True, capture_output=True)


def sql(query):
    return subprocess.run(
        ["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "ledger", "-d", "ledger", "-At", "-c", query],
        cwd=ROOT, check=True, capture_output=True, text=True).stdout.strip()


def wait_db(timeout=60):
    end = time.time() + timeout
    while time.time() < end:
        try:
            if sql("select 1") == "1":
                return
        except subprocess.CalledProcessError:
            pass
        time.sleep(0.5)
    raise RuntimeError("database did not come back")


class App:
    def __init__(self):
        jars = [j for j in (SERVICE / "target").glob("*.jar") if not j.name.endswith("-plain.jar")]
        if not jars:
            sys.exit("No jar found. Run: cd ledger-service && ./mvnw -q package -DskipTests")
        self.jar = jars[0]
        self.log = open(ROOT / "chaos" / "app.log", "a")
        self.proc = None

    def start(self):
        self.proc = subprocess.Popen(
            ["java", "-jar", str(self.jar), "--spring.datasource.hikari.connection-timeout=3000"],
            cwd=SERVICE, stdout=self.log, stderr=subprocess.STDOUT)
        end = time.time() + 90
        while time.time() < end:
            try:
                if http("GET", "/accounts/1", timeout=2)[0] == 200:
                    return
            except Exception:
                pass
            time.sleep(0.5)
        raise RuntimeError("app did not become healthy (see chaos/app.log)")

    def kill(self):
        if self.proc and self.proc.poll() is None:
            self.proc.kill()          # SIGKILL: no graceful shutdown
            self.proc.wait()


# ---------------------------------------------------------------- run
def main():
    print(f"Run {RUN_ID}: target {TARGET_CRASHES} crashes")
    wait_db()
    app = App()
    app.start()
    run_start = sql("select now()")

    # Setup: accounts + initial deposits (before any chaos)
    accounts = []
    for n in range(NUM_ACCOUNTS):
        status, data = http("POST", "/accounts", {"ownerName": f"chaos-{RUN_ID}-{n}", "currency": "INR"})
        assert status == 201, f"account create failed: {status}"
        accounts.append(data["id"])
        status, _, _ = send_with_retry("POST", f"/accounts/{data['id']}/deposit",
                                       {"amountMinor": INITIAL_DEPOSIT}, f"setup-{RUN_ID}-{n}")
        assert status in (200, 201), f"deposit failed: {status}"
    print(f"Accounts {accounts}, {INITIAL_DEPOSIT} each")

    results, results_lock = [], threading.Lock()
    counter = itertools.count()
    stop_payments = threading.Event()
    stats = {"app_kills": 0, "db_kills": 0}

    def pay(i):
        src, dst = random.sample(accounts, 2)
        amount = random.randint(1, 3000)
        key = f"chaos-{RUN_ID}-{i}"
        status, data, attempts = send_with_retry(
            "POST", "/transfers", {"fromAccountId": src, "toAccountId": dst, "amountMinor": amount}, key)
        return {"key": key, "from": src, "to": dst, "amount": amount, "status": status,
                "transfer_id": data["id"] if data else None, "attempts": attempts}

    def worker():
        while not stop_payments.is_set():
            r = pay(next(counter))
            with results_lock:
                results.append(r)
            time.sleep(random.uniform(0.05, 0.2))

    def chaos():
        crashes = 0
        while crashes < TARGET_CRASHES:
            time.sleep(random.uniform(2, 5))
            crashes += 1
            if random.random() < 0.7:
                app.kill()
                stats["app_kills"] += 1
                print(f"[chaos {crashes}/{TARGET_CRASHES}] kill -9 app ... ", end="", flush=True)
                app.start()
            else:
                compose("kill", "postgres")
                stats["db_kills"] += 1
                print(f"[chaos {crashes}/{TARGET_CRASHES}] kill postgres ... ", end="", flush=True)
                time.sleep(2)
                compose("start", "postgres")
                wait_db()
            print(f"back up ({len(results)} payments resolved so far)")
        stop_payments.set()

    workers = [threading.Thread(target=worker) for _ in range(WORKERS)]
    chaos_thread = threading.Thread(target=chaos)
    for t in workers:
        t.start()
    chaos_thread.start()
    chaos_thread.join()
    for t in workers:
        t.join()

    # ------------------------------------------------------------ audit
    wait_db()
    run_keys = {}
    for line in sql(f"select idem_key, transfer_id from idempotency_keys where idem_key like 'chaos-{RUN_ID}-%'").splitlines():
        k, tid = line.split("|")
        run_keys[k] = tid

    ok = [r for r in results if r["status"] in (200, 201)]
    rejected = [r for r in results if r["status"] == 409]
    unresolved = [r for r in results if r["status"] is None]
    unexpected = [r for r in results if r["status"] not in (200, 201, 409, None)]

    expected = {a: INITIAL_DEPOSIT for a in accounts}
    for r in ok:
        expected[r["from"]] -= r["amount"]
        expected[r["to"]] += r["amount"]
    actual = {int(a): int(b) for a, b in (l.split("|") for l in sql(
        f"select id, balance_minor from accounts where id in ({','.join(map(str, accounts))})").splitlines())}

    checks = {
        "money conserved (total of all balances unchanged)":
            int(sql("select sum(balance_minor) from accounts")) == RESERVE_TOTAL,
        "every balance equals the sum of its ledger entries":
            sql("""select count(*) from accounts a
                   left join (select account_id, sum(amount_minor) s from ledger_entries group by account_id) e
                   on e.account_id = a.id
                   where a.balance_minor - coalesce(e.s, 0) <> case when a.id = 1 then 1000000000000 else 0 end""") == "0",
        "every transfer has exactly 2 entries summing to 0":
            sql("""select count(*) from transfers t
                   left join (select transfer_id, count(*) c, sum(amount_minor) s from ledger_entries group by transfer_id) e
                   on e.transfer_id = t.id
                   where coalesce(e.c, 0) <> 2 or coalesce(e.s, 0) <> 0""") == "0",
        "no lost payments (every success the client saw is in the DB)":
            all(r["key"] in run_keys for r in ok),
        "client was told the same transfer id the DB stored":
            all(run_keys.get(r["key"]) == str(r["transfer_id"]) for r in ok),
        "no duplicates (each key maps to its own single transfer)":
            len(run_keys) == len(set(run_keys.values())),
        "rejected payments (409) left nothing behind":
            all(r["key"] not in run_keys for r in rejected),
        "no phantom transfers (no transfer without a key during the run)":
            sql(f"""select count(*) from transfers t where t.created_at >= '{run_start}'
                    and not exists (select 1 from idempotency_keys k where k.transfer_id = t.id)""") == "0",
        "final balances match what the client expects":
            actual == expected,
        "every request resolved and no unexpected statuses":
            not unresolved and not unexpected,
    }

    app.kill()

    print("\n" + "=" * 64)
    print(f"Run {RUN_ID}")
    print(f"Crashes: {stats['app_kills']} app kill -9 + {stats['db_kills']} Postgres kills "
          f"= {stats['app_kills'] + stats['db_kills']}")
    print(f"Payments: {len(results)} total | {sum(r['status'] == 201 for r in results)} created | "
          f"{sum(r['status'] == 200 for r in results)} recovered by retry (replayed) | "
          f"{len(rejected)} rejected (insufficient funds) | {len(unresolved)} unresolved")
    print(f"Requests that needed at least one retry: {sum(r['attempts'] > 1 for r in results)}")
    print("-" * 64)
    for name, passed in checks.items():
        print(f"[{'PASS' if passed else 'FAIL'}] {name}")
    print("=" * 64)

    out = ROOT / "chaos" / f"results-{RUN_ID}.json"
    out.write_text(json.dumps({"run_id": RUN_ID, "stats": stats, "checks": checks, "results": results}, indent=1))
    print(f"Saved {out}")
    sys.exit(0 if all(checks.values()) else 1)


if __name__ == "__main__":
    main()
