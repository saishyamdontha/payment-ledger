-- WHY: money stored as BIGINT in paise (minor units). Never use float/double for money.
CREATE TABLE accounts (
    id            BIGSERIAL PRIMARY KEY,
    owner_name    VARCHAR(100) NOT NULL,
    currency      VARCHAR(3)   NOT NULL,
    balance_minor BIGINT       NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT balance_non_negative CHECK (balance_minor >= 0)
);

CREATE TABLE transfers (
    id              BIGSERIAL PRIMARY KEY,
    from_account_id BIGINT      NOT NULL REFERENCES accounts(id),
    to_account_id   BIGINT      NOT NULL REFERENCES accounts(id),
    amount_minor    BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency        VARCHAR(3)  NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT different_accounts CHECK (from_account_id <> to_account_id)
);

-- WHY: double-entry. Every transfer writes 2 rows (-amount, +amount) that sum to zero.
CREATE TABLE ledger_entries (
    id           BIGSERIAL PRIMARY KEY,
    transfer_id  BIGINT      NOT NULL REFERENCES transfers(id),
    account_id   BIGINT      NOT NULL REFERENCES accounts(id),
    amount_minor BIGINT      NOT NULL CHECK (amount_minor <> 0),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_entries_account ON ledger_entries(account_id);

-- WHY: money never appears from nowhere. Deposits are transfers FROM this reserve account,
-- so the total of all balances is always constant. That's an invariant we can test.
INSERT INTO accounts (owner_name, currency, balance_minor)
VALUES ('SYSTEM_RESERVE', 'INR', 1000000000000);
