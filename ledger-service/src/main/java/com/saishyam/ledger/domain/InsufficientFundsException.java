package com.saishyam.ledger.domain;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(Long accountId, long balance, long requested) {
        super("Account " + accountId + " has " + balance + " but transfer needs " + requested);
    }
}
