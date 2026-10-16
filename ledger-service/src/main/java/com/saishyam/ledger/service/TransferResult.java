package com.saishyam.ledger.service;

import com.saishyam.ledger.domain.Transfer;

public record TransferResult(Transfer transfer, boolean replayed) {}
