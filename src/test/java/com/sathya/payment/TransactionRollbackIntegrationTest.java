package com.sathya.payment;

import com.sathya.payment.dto.AccountResponse;
import com.sathya.payment.dto.CreateAccountRequest;
import com.sathya.payment.dto.DepositRequest;
import com.sathya.payment.dto.TransferRequest;
import com.sathya.payment.entity.LedgerEntry;
import com.sathya.payment.entity.Transaction;
import com.sathya.payment.exception.InsufficientFundsException;
import com.sathya.payment.repository.AccountRepository;
import com.sathya.payment.repository.LedgerEntryRepository;
import com.sathya.payment.repository.TransactionRepository;
import com.sathya.payment.service.AccountService;
import com.sathya.payment.service.TransferService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a failed transfer rolls back atomically: no balance
 * mutations, no Transaction row, and no LedgerEntry rows must be
 * committed when the service throws an exception.
 */
@SpringBootTest
class TransactionRollbackIntegrationTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Test
    void failedTransferDueToInsufficientFundsMustNotLeavePartialState() {

        String suffix = UUID.randomUUID().toString();

        // 1. Create accounts.
        AccountResponse alice = accountService.createAccount(
                new CreateAccountRequest("alice-rollback-" + suffix, "USD")
        );

        AccountResponse bob = accountService.createAccount(
                new CreateAccountRequest("bob-rollback-" + suffix, "USD")
        );

        // 2. Give Alice exactly $100.
        accountService.deposit(
                alice.id(),
                new DepositRequest(new BigDecimal("100"))
        );

        // Capture Alice's current ledger snapshot before the failing transfer.
        List<LedgerEntry> aliceEntriesBefore =
                ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(alice.id());

        // 3. Attempt to transfer $150 — must throw InsufficientFundsException.
        String idempotencyKey = "ROLLBACK-INSUF-" + suffix;

        assertThrows(
                InsufficientFundsException.class,
                () -> transferService.transfer(
                        idempotencyKey,
                        new TransferRequest(
                                alice.id(),
                                bob.id(),
                                new BigDecimal("150")
                        )
                ),
                "A transfer of $150 from an account with $100 must throw InsufficientFundsException"
        );

        // 4. Alice's balance must be unchanged.
        AccountResponse updatedAlice = accountService.getAccount(alice.id());
        assertEquals(
                0,
                updatedAlice.balance().compareTo(new BigDecimal("100")),
                "Alice's balance must still be $100 after a failed transfer"
        );

        // 5. Bob's balance must remain $0.
        AccountResponse updatedBob = accountService.getAccount(bob.id());
        assertEquals(
                0,
                updatedBob.balance().compareTo(BigDecimal.ZERO),
                "Bob's balance must remain $0 after a failed transfer"
        );

        // 6. No new Transaction row must have been committed.
        List<Transaction> allTransactions = transactionRepository.findAll();
        boolean failedTransactionCommitted = allTransactions.stream()
                .anyMatch(tx ->
                        tx.getSourceAccount().getId().equals(alice.id())
                        && tx.getTargetAccount().getId().equals(bob.id())
                        && tx.getAmount().compareTo(new BigDecimal("150")) == 0
                );

        assertTrue(
                !failedTransactionCommitted,
                "No Transaction row must be committed for a failed transfer"
        );

        // 7. No new LedgerEntry rows for Alice must have been committed.
        List<LedgerEntry> aliceEntriesAfter =
                ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(alice.id());

        assertEquals(
                aliceEntriesBefore.size(),
                aliceEntriesAfter.size(),
                "No new LedgerEntry rows must be committed for Alice after a failed transfer"
        );

        // 8. No LedgerEntry rows for Bob must exist at all.
        List<LedgerEntry> bobEntries =
                ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(bob.id());

        assertTrue(
                bobEntries.isEmpty(),
                "Bob must have no LedgerEntry rows after a failed transfer"
        );
    }

    /**
     * Alice holds USD and David holds EUR. A transfer attempt between
     * mismatched currencies must be rejected and leave both accounts untouched.
     */
    @Test
    void failedTransferDueToCurrencyMismatchMustNotLeavePartialState() {

        String suffix = UUID.randomUUID().toString();

        // 1. Create accounts with different currencies.
        AccountResponse alice = accountService.createAccount(
                new CreateAccountRequest("alice-currency-" + suffix, "USD")
        );

        AccountResponse david = accountService.createAccount(
                new CreateAccountRequest("david-currency-" + suffix, "EUR")
        );

        // 2. Fund both accounts.
        accountService.deposit(
                alice.id(),
                new DepositRequest(new BigDecimal("200"))
        );

        accountService.deposit(
                david.id(),
                new DepositRequest(new BigDecimal("100"))
        );

        // 3. Attempt a cross-currency transfer — must throw.
        String idempotencyKey = "ROLLBACK-CURRENCY-" + suffix;

        assertThrows(
                Exception.class,
                () -> transferService.transfer(
                        idempotencyKey,
                        new TransferRequest(
                                alice.id(),
                                david.id(),
                                new BigDecimal("50")
                        )
                ),
                "A transfer between accounts with different currencies must be rejected"
        );

        // 4. Alice's balance must be unchanged at $200.
        AccountResponse updatedAlice = accountService.getAccount(alice.id());
        assertEquals(
                0,
                updatedAlice.balance().compareTo(new BigDecimal("200")),
                "Alice's balance must remain $200 after a failed cross-currency transfer"
        );

        // 5. David's balance must be unchanged at 100 EUR.
        AccountResponse updatedDavid = accountService.getAccount(david.id());
        assertEquals(
                0,
                updatedDavid.balance().compareTo(new BigDecimal("100")),
                "David's balance must remain 100 EUR after a failed cross-currency transfer"
        );

        // 6. No Transaction row linking Alice to David must be committed.
        List<Transaction> allTransactions = transactionRepository.findAll();
        boolean failedTransactionCommitted = allTransactions.stream()
                .anyMatch(tx ->
                        tx.getSourceAccount().getId().equals(alice.id())
                        && tx.getTargetAccount().getId().equals(david.id())
                );

        assertTrue(
                !failedTransactionCommitted,
                "No Transaction row must be committed for a failed cross-currency transfer"
        );

        // 7. David must have no LedgerEntry rows at all — deposits are balance-only
        //    operations and do not produce ledger entries, so a failed transfer must
        //    leave David with zero entries.
        List<LedgerEntry> davidEntries =
                ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(david.id());

        assertTrue(
                davidEntries.isEmpty(),
                "David must have no LedgerEntry rows after a failed cross-currency transfer"
        );
    }

    /**
     * An attempt to transfer to oneself must be rejected immediately
     * before any database mutation occurs.
     */
    @Test
    void failedSelfTransferMustNotLeavePartialState() {

        String suffix = UUID.randomUUID().toString();

        AccountResponse alice = accountService.createAccount(
                new CreateAccountRequest("alice-self-" + suffix, "USD")
        );

        accountService.deposit(
                alice.id(),
                new DepositRequest(new BigDecimal("100"))
        );

        List<LedgerEntry> entriesBefore =
                ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(alice.id());

        String idempotencyKey = "ROLLBACK-SELF-" + suffix;

        assertThrows(
                Exception.class,
                () -> transferService.transfer(
                        idempotencyKey,
                        new TransferRequest(
                                alice.id(),
                                alice.id(),      // same source and target
                                new BigDecimal("10")
                        )
                ),
                "A self-transfer must be rejected"
        );

        // Balance unchanged.
        AccountResponse updatedAlice = accountService.getAccount(alice.id());
        assertEquals(
                0,
                updatedAlice.balance().compareTo(new BigDecimal("100")),
                "Alice's balance must be unchanged after a failed self-transfer"
        );

        // No new ledger entries.
        List<LedgerEntry> entriesAfter =
                ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(alice.id());

        assertEquals(
                entriesBefore.size(),
                entriesAfter.size(),
                "No new LedgerEntry rows must be created for a failed self-transfer"
        );
    }
}
