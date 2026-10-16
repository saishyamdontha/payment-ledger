package com.saishyam.ledger.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_name", nullable = false)
    private String ownerName;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Account() {}

    public Account(String ownerName, String currency) {
        this.ownerName = ownerName;
        this.currency = currency;
        this.balanceMinor = 0;
    }

    public void debit(long amountMinor) {
        if (balanceMinor < amountMinor) {
            throw new InsufficientFundsException(id, balanceMinor, amountMinor);
        }
        balanceMinor -= amountMinor;
    }

    public void credit(long amountMinor) {
        balanceMinor += amountMinor;
    }

    public Long getId() { return id; }
    public String getOwnerName() { return ownerName; }
    public String getCurrency() { return currency; }
    public long getBalanceMinor() { return balanceMinor; }
    public Instant getCreatedAt() { return createdAt; }
}
