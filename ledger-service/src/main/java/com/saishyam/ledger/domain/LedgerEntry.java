package com.saishyam.ledger.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transfer_id", nullable = false)
    private Long transferId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;   // negative = debit, positive = credit

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected LedgerEntry() {}

    public LedgerEntry(Long transferId, Long accountId, long amountMinor) {
        this.transferId = transferId;
        this.accountId = accountId;
        this.amountMinor = amountMinor;
    }

    public Long getId() { return id; }
    public Long getTransferId() { return transferId; }
    public Long getAccountId() { return accountId; }
    public long getAmountMinor() { return amountMinor; }
    public Instant getCreatedAt() { return createdAt; }
}
