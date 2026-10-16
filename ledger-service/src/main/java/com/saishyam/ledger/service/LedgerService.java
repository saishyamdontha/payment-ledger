package com.saishyam.ledger.service;

import com.saishyam.ledger.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class LedgerService {

    public static final long RESERVE_ACCOUNT_ID = 1L;

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final LedgerEntryRepository entries;

    public LedgerService(AccountRepository accounts, TransferRepository transfers,
                         LedgerEntryRepository entries) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.entries = entries;
    }

    @Transactional
    public Account openAccount(String ownerName, String currency) {
        return accounts.save(new Account(ownerName, currency.toUpperCase()));
    }

    // WHY @Transactional: balance updates, transfer row and both entries
    // are saved together or not at all. No half-finished transfers.
    @Transactional
    public Transfer transfer(long fromId, long toId, long amountMinor) {
        if (amountMinor <= 0) throw new InvalidTransferException("Amount must be positive");
        if (fromId == toId) throw new InvalidTransferException("Cannot transfer to the same account");

        // WHY: always lock the lower id first. If A->B and B->A run at the same time,
        // both lock in the same order, so they can't deadlock waiting on each other.
        long firstId = Math.min(fromId, toId);
        long secondId = Math.max(fromId, toId);
        Account first = lock(firstId);
        Account second = lock(secondId);
        Account from = (fromId == firstId) ? first : second;
        Account to = (fromId == firstId) ? second : first;

        if (!from.getCurrency().equals(to.getCurrency())) {
            throw new InvalidTransferException("Currency mismatch: " + from.getCurrency() + " vs " + to.getCurrency());
        }

        from.debit(amountMinor);   // throws if insufficient funds -> whole transaction rolls back
        to.credit(amountMinor);

        Transfer transfer = transfers.save(new Transfer(fromId, toId, amountMinor, from.getCurrency()));
        entries.save(new LedgerEntry(transfer.getId(), fromId, -amountMinor));
        entries.save(new LedgerEntry(transfer.getId(), toId, amountMinor));
        return transfer;
    }

    @Transactional
    public Transfer deposit(long accountId, long amountMinor) {
        return transfer(RESERVE_ACCOUNT_ID, accountId, amountMinor);
    }

    @Transactional(readOnly = true)
    public Account getAccount(long id) {
        return accounts.findById(id).orElseThrow(() -> new NotFoundException("Account " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public List<LedgerEntry> entriesFor(long accountId) {
        getAccount(accountId);
        return entries.findByAccountIdOrderByIdAsc(accountId);
    }

    private Account lock(long id) {
        return accounts.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Account " + id + " not found"));
    }
}
