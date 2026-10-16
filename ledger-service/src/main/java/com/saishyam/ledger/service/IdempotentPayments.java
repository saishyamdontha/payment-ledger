package com.saishyam.ledger.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

// WHY a separate, NON-transactional class: if two duplicates race and one hits the
// PRIMARY KEY violation, its transaction is already rolled back. We catch that here,
// outside it, and return the transfer the winner committed.
@Service
public class IdempotentPayments {

    private final LedgerService ledger;

    public IdempotentPayments(LedgerService ledger) { this.ledger = ledger; }

    public TransferResult transfer(String key, long fromId, long toId, long amountMinor) {
        try {
            return ledger.transfer(key, fromId, toId, amountMinor);
        } catch (DataIntegrityViolationException e) {
            return ledger.findReplay(key, LedgerService.requestHash(fromId, toId, amountMinor))
                    .orElseThrow(() -> e);
        }
    }

    public TransferResult deposit(String key, long accountId, long amountMinor) {
        return transfer(key, LedgerService.RESERVE_ACCOUNT_ID, accountId, amountMinor);
    }
}
