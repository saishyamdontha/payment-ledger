-- WHY: the PRIMARY KEY is the safety net. Even if two identical requests race,
-- the database allows only one row per key, so only one transfer can commit.
CREATE TABLE idempotency_keys (
    idem_key     VARCHAR(100) PRIMARY KEY,
    request_hash VARCHAR(64)  NOT NULL,
    transfer_id  BIGINT       NOT NULL REFERENCES transfers(id),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
