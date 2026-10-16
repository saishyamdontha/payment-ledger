package com.saishyam.ledger.domain;

public class InvalidTransferException extends RuntimeException {
    public InvalidTransferException(String message) { super(message); }
}
