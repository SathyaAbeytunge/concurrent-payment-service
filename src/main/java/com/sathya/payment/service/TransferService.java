
package com.sathya.payment.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.sathya.payment.dto.TransferRequest;
import com.sathya.payment.dto.TransferResponse;
import com.sathya.payment.entity.Account;
import com.sathya.payment.entity.IdempotencyKey;
import com.sathya.payment.entity.LedgerEntry;
import com.sathya.payment.entity.Transaction;
import com.sathya.payment.exception.AccountNotFoundException;
import com.sathya.payment.exception.IdempotencyConflictException;
import com.sathya.payment.exception.InsufficientFundsException;
import com.sathya.payment.exception.InvalidTransferException;
import com.sathya.payment.repository.AccountRepository;
import com.sathya.payment.repository.IdempotencyKeyRepository;
import com.sathya.payment.repository.LedgerEntryRepository;
import com.sathya.payment.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransferService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public TransferResponse transfer(
            String idempotencyKey,
            TransferRequest request) {

        if (idempotencyKey == null
                || idempotencyKey.isBlank()
                || idempotencyKey.length() > 128) {
            throw new InvalidTransferException(
                    "Idempotency-Key must contain 1 to 128 characters"
            );
        }

        String requestHash = createRequestHash(request);

        int inserted = idempotencyKeyRepository.tryCreate(
                idempotencyKey,
                requestHash,
                OffsetDateTime.now().plusHours(24)
        );

        if (inserted == 0) {
            IdempotencyKey existing = idempotencyKeyRepository
                    .findById(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "Existing idempotency key was not found"
                    ));

            if (!existing.getRequestHash().equals(requestHash)) {
                throw new IdempotencyConflictException(
                        "This idempotency key was already used "
                                + "for a different request"
                );
            }

            if (existing.getStatus()
                    == IdempotencyKey.IdempotencyStatus.COMPLETED
                    && existing.getResponseBody() != null) {
                return readSavedResponse(existing.getResponseBody());
            }

            throw new IdempotencyConflictException(
                    "This idempotency key has no completed result"
            );
        }

        // Reject transfers to the same account.
        if (request.sourceAccountId().equals(request.targetAccountId())) {
            throw new InvalidTransferException(
                    "Source and target accounts must be different"
            );
        }

        // Lock accounts in a consistent order to reduce deadlocks.
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

        TransferResponse response = new TransferResponse(
                transaction.getId(),
                sourceAccount.getId(),
                targetAccount.getId(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getStatus(),
                transaction.getCreatedAt()
        );

        // Save the exact result for future retries with the same key.
        IdempotencyKey savedKey = idempotencyKeyRepository
                .findById(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency key was not found"
                ));

        savedKey.setStatus(IdempotencyKey.IdempotencyStatus.COMPLETED);
        savedKey.setResponseCode(200);
        savedKey.setResponseBody(writeResponse(response));

        idempotencyKeyRepository.save(savedKey);

        return response;
    }

    private String createRequestHash(TransferRequest request) {
        String requestData =
                request.sourceAccountId() + "|"
                + request.targetAccountId() + "|"
                + request.amount().stripTrailingZeros().toPlainString();

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            return HexFormat.of().formatHex(
                    digest.digest(
                            requestData.getBytes(StandardCharsets.UTF_8)
                    )
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "Could not calculate request hash",
                    exception
            );
        }
    }

    private String writeResponse(TransferResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Could not save transfer response",
                    exception
            );
        }
    }

    private TransferResponse readSavedResponse(String responseBody) {
        try {
            return objectMapper.readValue(
                    responseBody,
                    TransferResponse.class
            );
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Could not read saved transfer response",
                    exception
            );
        }
    }
}
