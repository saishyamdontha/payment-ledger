package com.saishyam.ledger.service;

import com.saishyam.ledger.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Service
public class LedgerService {

    public static final long RESERVE_ACCOUNT_ID = 1L;

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final LedgerEntryRepository entries;
    private final IdempotencyKeyRepository idempotencyKeys;

    public LedgerService(AccountRepository accounts, TransferRepository transfers,
                         LedgerEntryRepository entries, IdempotencyKeyRepository idempotencyKeys) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.entries = entries;
        this.idempotencyKeys = idempotencyKeys;
    }

    @Transactional
    public Account openAccount(String ownerName, String currency) {
        return accounts.save(new Account(ownerName, currency.toUpperCase()));
    }

    // Session-1 signatures kept so existing tests still work (no idempotency key).
    @Transactional
    public Transfer transfer(long fromId, long toId, long amountMinor) {
        return transfer(null, fromId, toId, amountMinor).transfer();
    }

    @Transactional
    public Transfer deposit(long accountId, long amountMinor) {
        return transfer(RESERVE_ACCOUNT_ID, accountId, amountMinor);
    }

    @Transactional
    public TransferResult transfer(String idemKey, long fromId, long toId, long amountMinor) {
        if (amountMinor <= 0) throw new InvalidTransferException("Amount must be positive");
        if (fromId == toId) throw new InvalidTransferException("Cannot transfer to the same account");

        long firstId = Math.min(fromId, toId);
        long secondId = Math.max(fromId, toId);
        Account first = lock(firstId);
        Account second = lock(secondId);

        // WHY check AFTER locking: a duplicate request waits on the same row locks,
        // so by the time it gets here the first request has committed its key and we replay it.
        String hash = requestHash(fromId, toId, amountMinor);
        if (idemKey != null) {
            Optional<TransferResult> replay = findReplay(idemKey, hash);
            if (replay.isPresent()) return replay.get();
        }

        Account from = (fromId == firstId) ? first : second;
        Account to = (fromId == firstId) ? second : first;
        if (!from.getCurrency().equals(to.getCurrency())) {
            throw new InvalidTransferException("Currency mismatch: " + from.getCurrency() + " vs " + to.getCurrency());
        }

        from.debit(amountMinor);
        to.credit(amountMinor);

        Transfer transfer = transfers.save(new Transfer(fromId, toId, amountMinor, from.getCurrency()));
        entries.save(new LedgerEntry(transfer.getId(), fromId, -amountMinor));
        entries.save(new LedgerEntry(transfer.getId(), toId, amountMinor));

        if (idemKey != null) {
            // saveAndFlush: hit the PRIMARY KEY constraint now, inside this transaction,
            // so a racing duplicate rolls back its whole transfer.
            idempotencyKeys.saveAndFlush(new IdempotencyKey(idemKey, hash, transfer.getId()));
        }
        return new TransferResult(transfer, false);
    }

    @Transactional(readOnly = true)
    public Optional<TransferResult> findReplay(String idemKey, String hash) {
        return idempotencyKeys.findById(idemKey).map(k -> {
            if (!k.getRequestHash().equals(hash)) throw new IdempotencyConflictException(idemKey);
            Transfer original = transfers.findById(k.getTransferId()).orElseThrow();
            return new TransferResult(original, true);
        });
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

    public static String requestHash(long fromId, long toId, long amountMinor) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((fromId + "|" + toId + "|" + amountMinor).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Account lock(long id) {
        return accounts.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Account " + id + " not found"));
    }
}
