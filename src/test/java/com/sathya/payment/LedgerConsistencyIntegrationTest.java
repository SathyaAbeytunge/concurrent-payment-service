
package com.sathya.payment;

import com.sathya.payment.dto.AccountResponse;
import com.sathya.payment.dto.CreateAccountRequest;
import com.sathya.payment.dto.DepositRequest;
import com.sathya.payment.dto.TransferRequest;
import com.sathya.payment.dto.TransferResponse;
import com.sathya.payment.entity.LedgerEntry;
import com.sathya.payment.entity.Transaction;
import com.sathya.payment.repository.LedgerEntryRepository;
import com.sathya.payment.repository.TransactionRepository;
import com.sathya.payment.service.AccountService;
import com.sathya.payment.service.TransferService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
class LedgerConsistencyIntegrationTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Test
    void successfulTransferShouldCreateConsistentLedgerEntries() {

        // Create Alice's and Bob's accounts.
        AccountResponse alice = accountService.createAccount(
                new CreateAccountRequest("alice-ledger-test", "USD")
        );

        AccountResponse bob = accountService.createAccount(
                new CreateAccountRequest("bob-ledger-test", "USD")
        );

        // Deposit $100 into Alice's account.
        accountService.deposit(
                alice.id(),
                new DepositRequest(new BigDecimal("100"))
        );

        // Transfer $80 from Alice to Bob.
        TransferResponse response = transferService.transfer(
                "LEDGER-TEST-" + java.util.UUID.randomUUID(),
                new TransferRequest(
                        alice.id(),
                        bob.id(),
                        new BigDecimal("80")
                )
        );

        // Verify the transaction was saved successfully.
        Transaction transaction = transactionRepository
                .findById(response.transactionId())
                .orElseThrow();

        assertEquals(
                Transaction.TransactionStatus.SUCCESS,
                transaction.getStatus()
        );

        assertEquals(
                0,
                transaction.getAmount().compareTo(new BigDecimal("80"))
        );

        // Verify exactly two ledger entries exist for this transaction.
        List<LedgerEntry> entries =
                ledgerEntryRepository.findByTransactionId(
                        transaction.getId()
                );

        assertEquals(2, entries.size());

        LedgerEntry debit = entries.stream()
                .filter(entry ->
                        entry.getType() == LedgerEntry.EntryType.DEBIT)
                .findFirst()
                .orElseThrow();

        LedgerEntry credit = entries.stream()
                .filter(entry ->
                        entry.getType() == LedgerEntry.EntryType.CREDIT)
                .findFirst()
                .orElseThrow();

        // Both entries must record the transferred amount.
        assertEquals(
                0,
                debit.getAmount().compareTo(new BigDecimal("80"))
        );

        assertEquals(
                0,
                credit.getAmount().compareTo(new BigDecimal("80"))
        );

        // Verify each entry belongs to the correct account.
        assertEquals(alice.id(), debit.getAccount().getId());
        assertEquals(bob.id(), credit.getAccount().getId());

        // Verify the balances recorded in the ledger.
        assertEquals(
                0,
                debit.getBalanceAfter().compareTo(new BigDecimal("20"))
        );

        assertEquals(
                0,
                credit.getBalanceAfter().compareTo(new BigDecimal("80"))
        );

        // Verify the final account balances.
        AccountResponse updatedAlice =
                accountService.getAccount(alice.id());

        AccountResponse updatedBob =
                accountService.getAccount(bob.id());

        assertEquals(
                0,
                updatedAlice.balance().compareTo(new BigDecimal("20"))
        );

        assertEquals(
                0,
                updatedBob.balance().compareTo(new BigDecimal("80"))
        );

        assertNotNull(debit.getId());
        assertNotNull(credit.getId());
    }
}
