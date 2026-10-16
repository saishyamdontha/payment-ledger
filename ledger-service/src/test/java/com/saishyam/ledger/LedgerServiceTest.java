package com.saishyam.ledger;

import com.saishyam.ledger.domain.*;
import com.saishyam.ledger.service.LedgerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class LedgerServiceTest {

    static final long TOTAL_MONEY = 1_000_000_000_000L;   // seeded in the reserve account

    @Autowired LedgerService ledger;
    @Autowired AccountRepository accounts;
    @Autowired LedgerEntryRepository entries;

    @Test
    void concurrentTransfersNeverOverspend() throws Exception {
        Account a = ledger.openAccount("Alice", "INR");
        Account b = ledger.openAccount("Bob", "INR");
        ledger.deposit(a.getId(), 50);

        int attempts = 100;
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < attempts; i++) {
            pool.submit(() -> {
                start.await();
                try {
                    ledger.transfer(a.getId(), b.getId(), 1);
                    ok.incrementAndGet();
                } catch (InsufficientFundsException e) {
                    rejected.incrementAndGet();
                }
                return null;
            });
        }
        start.countDown();          // release all threads at once
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        System.out.printf("RESULT: %d attempts, %d succeeded, %d rejected%n", attempts, ok.get(), rejected.get());

        assertEquals(50, ok.get());
        assertEquals(50, rejected.get());
        assertEquals(0, ledger.getAccount(a.getId()).getBalanceMinor());
        assertEquals(50, ledger.getAccount(b.getId()).getBalanceMinor());
        assertBalanceMatchesEntries(a.getId());
        assertBalanceMatchesEntries(b.getId());
    }

    @Test
    void totalMoneyIsConserved() {
        long total = accounts.findAll().stream().mapToLong(Account::getBalanceMinor).sum();
        assertEquals(TOTAL_MONEY, total);
    }

    @Test
    void overdraftIsRejectedAndNothingChanges() {
        Account a = ledger.openAccount("Carol", "INR");
        Account b = ledger.openAccount("Dan", "INR");
        ledger.deposit(a.getId(), 10);

        assertThrows(InsufficientFundsException.class, () -> ledger.transfer(a.getId(), b.getId(), 11));
        assertEquals(10, ledger.getAccount(a.getId()).getBalanceMinor());
        assertEquals(0, ledger.getAccount(b.getId()).getBalanceMinor());
    }

    private void assertBalanceMatchesEntries(long accountId) {
        long sum = entries.findByAccountIdOrderByIdAsc(accountId).stream()
                .mapToLong(LedgerEntry::getAmountMinor).sum();
        assertEquals(ledger.getAccount(accountId).getBalanceMinor(), sum);
    }
}
