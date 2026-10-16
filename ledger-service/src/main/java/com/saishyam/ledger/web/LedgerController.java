package com.saishyam.ledger.web;

import com.saishyam.ledger.domain.Account;
import com.saishyam.ledger.domain.LedgerEntry;
import com.saishyam.ledger.domain.Transfer;
import com.saishyam.ledger.service.IdempotentPayments;
import com.saishyam.ledger.service.LedgerService;
import com.saishyam.ledger.service.TransferResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
public class LedgerController {

    record OpenAccountRequest(@NotBlank String ownerName, @NotBlank @Size(min = 3, max = 3) String currency) {}
    record AmountRequest(@Positive long amountMinor) {}
    record TransferRequest(@NotNull Long fromAccountId, @NotNull Long toAccountId, @Positive long amountMinor) {}

    record AccountResponse(Long id, String ownerName, String currency, long balanceMinor) {
        static AccountResponse of(Account a) {
            return new AccountResponse(a.getId(), a.getOwnerName(), a.getCurrency(), a.getBalanceMinor());
        }
    }
    record TransferResponse(Long id, Long fromAccountId, Long toAccountId, long amountMinor,
                            String currency, Instant createdAt) {
        static TransferResponse of(Transfer t) {
            return new TransferResponse(t.getId(), t.getFromAccountId(), t.getToAccountId(),
                    t.getAmountMinor(), t.getCurrency(), t.getCreatedAt());
        }
    }
    record EntryResponse(Long id, Long transferId, long amountMinor, Instant createdAt) {
        static EntryResponse of(LedgerEntry e) {
            return new EntryResponse(e.getId(), e.getTransferId(), e.getAmountMinor(), e.getCreatedAt());
        }
    }

    private final LedgerService ledger;
    private final IdempotentPayments payments;

    public LedgerController(LedgerService ledger, IdempotentPayments payments) {
        this.ledger = ledger;
        this.payments = payments;
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse open(@Valid @RequestBody OpenAccountRequest req) {
        return AccountResponse.of(ledger.openAccount(req.ownerName(), req.currency()));
    }

    @GetMapping("/accounts/{id}")
    public AccountResponse get(@PathVariable long id) {
        return AccountResponse.of(ledger.getAccount(id));
    }

    @PostMapping("/accounts/{id}/deposit")
    public ResponseEntity<TransferResponse> deposit(@RequestHeader("Idempotency-Key") String key,
                                                    @PathVariable long id,
                                                    @Valid @RequestBody AmountRequest req) {
        return toResponse(payments.deposit(key, id, req.amountMinor()));
    }

    @PostMapping("/transfers")
    public ResponseEntity<TransferResponse> transfer(@RequestHeader("Idempotency-Key") String key,
                                                     @Valid @RequestBody TransferRequest req) {
        return toResponse(payments.transfer(key, req.fromAccountId(), req.toAccountId(), req.amountMinor()));
    }

    @GetMapping("/accounts/{id}/entries")
    public List<EntryResponse> entries(@PathVariable long id) {
        return ledger.entriesFor(id).stream().map(EntryResponse::of).toList();
    }

    // 201 = new transfer created; 200 + header = same request replayed, no money moved
    private ResponseEntity<TransferResponse> toResponse(TransferResult r) {
        return ResponseEntity.status(r.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header("Idempotent-Replayed", String.valueOf(r.replayed()))
                .body(TransferResponse.of(r.transfer()));
    }
}
