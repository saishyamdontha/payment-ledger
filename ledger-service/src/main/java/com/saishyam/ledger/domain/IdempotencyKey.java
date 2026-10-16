package com.saishyam.ledger.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    @Id
    @Column(name = "idem_key", length = 100)
    private String key;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "transfer_id", nullable = false)
    private Long transferId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected IdempotencyKey() {}

    public IdempotencyKey(String key, String requestHash, Long transferId) {
        this.key = key;
        this.requestHash = requestHash;
        this.transferId = transferId;
    }

    public String getKey() { return key; }
    public String getRequestHash() { return requestHash; }
    public Long getTransferId() { return transferId; }
}
