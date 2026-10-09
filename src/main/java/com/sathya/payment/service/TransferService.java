package com.sathya.payment.service;

import com.sathya.payment.dto.TransferRequest;
import com.sathya.payment.dto.TransferResponse;
import com.sathya.payment.entity.Account;
import com.sathya.payment.entity.LedgerEntry;
import com.sathya.payment.entity.Transaction;
import com.sathya.payment.exception.AccountNotFoundException;
import com.sathya.payment.exception.InsufficientFundsException;
import com.sathya.payment.exception.InvalidTransferException;
import com.sathya.payment.repository.AccountRepository;
import com.sathya.payment.repository.LedgerEntryRepository;
import com.sathya.payment.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransferService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    @Transactional
    public TransferResponse transfer(TransferRequest request) {

        if (request.sourceAccountId().equals(request.targetAccountId())) {
            throw new InvalidTransferException(
                    "Source and target accounts must be different"
            );
        }

        /*
         * Always lock accounts in the same order.
         *
         * This helps prevent deadlocks when two transfers
         * happen at the same time in opposite directions.
         */
        UUID firstId = request.sourceAccountId()
                .compareTo(request.targetAccountId()) < 0
                ? request.sourceAccountId()
                : request.targetAccountId();

        UUID secondId = request.sourceAccountId()
                .compareTo(request.targetAccountId()) < 0
                ? request.targetAccountId()
                : request.sourceAccountId();

        Account firstAccount = accountRepository.findByIdForUpdate(firstId)
                .orElseThrow(() -> new AccountNotFoundException(firstId));

        Account secondAccount = accountRepository.findByIdForUpdate(secondId)
                .orElseThrow(() -> new AccountNotFoundException(secondId));

        Account sourceAccount = firstAccount.getId()
                .equals(request.sourceAccountId())
                ? firstAccount
                : secondAccount;

        Account targetAccount = firstAccount.getId()
                .equals(request.targetAccountId())
                ? firstAccount
                : secondAccount;

        if (!sourceAccount.getCurrency().equals(targetAccount.getCurrency())) {
            throw new InvalidTransferException(
                    "Source and target currencies must match"
            );
        }

        if (sourceAccount.getBalance().compareTo(request.amount()) < 0) {
            throw new InsufficientFundsException();
        }

        OffsetDateTime now = OffsetDateTime.now();

        Transaction transaction = Transaction.builder()
                .sourceAccount(sourceAccount)
                .targetAccount(targetAccount)
                .amount(request.amount())
                .currency(sourceAccount.getCurrency())
                .status(Transaction.TransactionStatus.PENDING)
                .createdAt(now)
                .build();

        transaction = transactionRepository.save(transaction);

        sourceAccount.setBalance(
                sourceAccount.getBalance().subtract(request.amount())
        );

        targetAccount.setBalance(
                targetAccount.getBalance().add(request.amount())
        );

        sourceAccount.setUpdatedAt(now);
        targetAccount.setUpdatedAt(now);

        accountRepository.save(sourceAccount);
        accountRepository.save(targetAccount);

        LedgerEntry debit = LedgerEntry.builder()
                .transaction(transaction)
                .account(sourceAccount)
                .type(LedgerEntry.EntryType.DEBIT)
                .amount(request.amount())
                .balanceAfter(sourceAccount.getBalance())
                .createdAt(now)
                .build();

        LedgerEntry credit = LedgerEntry.builder()
                .transaction(transaction)
                .account(targetAccount)
                .type(LedgerEntry.EntryType.CREDIT)
                .amount(request.amount())
                .balanceAfter(targetAccount.getBalance())
                .createdAt(now)
                .build();

        ledgerEntryRepository.save(debit);
        ledgerEntryRepository.save(credit);

        transaction.setStatus(Transaction.TransactionStatus.SUCCESS);

        transactionRepository.save(transaction);

        return new TransferResponse(
                transaction.getId(),
                sourceAccount.getId(),
                targetAccount.getId(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getStatus(),
                transaction.getCreatedAt()
        );
    }
}