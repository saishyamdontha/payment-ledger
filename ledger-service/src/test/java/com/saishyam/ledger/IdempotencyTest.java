package com.saishyam.ledger;

import com.saishyam.ledger.domain.*;
import com.saishyam.ledger.service.IdempotentPayments;
import com.saishyam.ledger.service.LedgerService;
import com.saishyam.ledger.service.TransferResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class IdempotencyTest {

    @Autowired LedgerService ledger;
    @Autowired IdempotentPayments payments;

    @Test
    void sameKeyTwiceMovesMoneyOnce() {
        Account a = ledger.openAccount("Eve", "INR");
        String key = UUID.randomUUID().toString();

        TransferResult first = payments.deposit(key, a.getId(), 500);
        TransferResult second = payments.deposit(key, a.getId(), 500);

        assertFalse(first.replayed());
        assertTrue(second.replayed());
        assertEquals(first.transfer().getId(), second.transfer().getId());
        assertEquals(500, ledger.getAccount(a.getId()).getBalanceMinor());
    }

    @Test
    void sameKeyDifferentAmountIsRejected() {
        Account a = ledger.openAccount("Frank", "INR");
        String key = UUID.randomUUID().toString();

        payments.deposit(key, a.getId(), 500);
        assertThrows(IdempotencyConflictException.class, () -> payments.deposit(key, a.getId(), 900));
        assertEquals(500, ledger.getAccount(a.getId()).getBalanceMinor());
    }

    @Test
    void concurrentDuplicatesCreateExactlyOneTransfer() throws Exception {
        Account a = ledger.openAccount("Grace", "INR");
        String key = UUID.randomUUID().toString();

        int requests = 50;
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < requests; i++) {
            pool.submit(() -> {
                start.await();
                try {
                    if (payments.deposit(key, a.getId(), 100).replayed()) replayed.incrementAndGet();
                    else created.incrementAndGet();
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        System.out.printf("IDEMPOTENCY RESULT: %d duplicate requests, %d created, %d replayed, %d errors%n",
                requests, created.get(), replayed.get(), errors.get());

        assertEquals(1, created.get());
        assertEquals(requests - 1, replayed.get());
        assertEquals(0, errors.get());
        assertEquals(100, ledger.getAccount(a.getId()).getBalanceMinor());
    }
}
