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
import com.sathya.payment.gateway.RedisIdempotencyGateway;
import com.sathya.payment.repository.AccountRepository;
import com.sathya.payment.repository.IdempotencyKeyRepository;
import com.sathya.payment.repository.LedgerEntryRepository;
import com.sathya.payment.repository.TransactionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;

@Slf4j
@Service
public class TransferService {

    private static final Duration LOCK_TTL = Duration.ofMinutes(5);
    private static final Duration RESPONSE_TTL = Duration.ofHours(24);

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper;
    private final RedisIdempotencyGateway redisIdempotencyGateway;

    @Autowired
    public TransferService(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            LedgerEntryRepository ledgerEntryRepository,
            IdempotencyKeyRepository idempotencyKeyRepository,
            ObjectMapper objectMapper,
            @Autowired(required = false) RedisIdempotencyGateway redisIdempotencyGateway) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.objectMapper = objectMapper;
        this.redisIdempotencyGateway = redisIdempotencyGateway;
    }

    public TransferService(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            LedgerEntryRepository ledgerEntryRepository,
            IdempotencyKeyRepository idempotencyKeyRepository,
            ObjectMapper objectMapper) {
        this(accountRepository, transactionRepository, ledgerEntryRepository, idempotencyKeyRepository, objectMapper, null);
    }

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

        // 1. Fast-Path: Check Redis Cache & In-Flight Lock
        if (redisIdempotencyGateway != null) {
            try {
                String cached = redisIdempotencyGateway.get(idempotencyKey);
                if (cached != null) {
                    if (cached.startsWith("COMPLETED:")) {
                        String[] parts = cached.split(":", 3);
                        if (parts.length == 3) {
                            String savedHash = parts[1];
                            String responseBody = parts[2];
                            if (!savedHash.equals(requestHash)) {
                                throw new IdempotencyConflictException(
                                        "This idempotency key was already used for a different request"
                                );
                            }
                            return readSavedResponse(responseBody);
                        }
                    } else if (cached.startsWith("PROCESSING:")) {
                        String[] parts = cached.split(":", 2);
                        if (parts.length == 2 && !parts[1].equals(requestHash)) {
                            throw new IdempotencyConflictException(
                                    "This idempotency key was already used for a different request"
                            );
                        }
                        throw new IdempotencyConflictException(
                                "This idempotency key has no completed result"
                        );
                    }
                }
            } catch (IdempotencyConflictException exception) {
                throw exception;
            } catch (Exception exception) {
                log.warn("Redis idempotency cache check failed, falling back to database: {}", exception.getMessage());
            }
        }

        // 2. Authoritative Persistence: Attempt atomic creation in PostgreSQL
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
                populateRedisCache(idempotencyKey, requestHash, existing.getResponseBody());
                return readSavedResponse(existing.getResponseBody());
            }

            throw new IdempotencyConflictException(
                    "This idempotency key has no completed result"
            );
        }

        // 3. Key successfully claimed: Set in-flight lock in Redis
        setRedisInFlightLock(idempotencyKey, requestHash);

        try {
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

            String responseBody = writeResponse(response);
            savedKey.setStatus(IdempotencyKey.IdempotencyStatus.COMPLETED);
            savedKey.setResponseCode(200);
            savedKey.setResponseBody(responseBody);

            idempotencyKeyRepository.save(savedKey);

            // Cache the completed response in Redis
            populateRedisCache(idempotencyKey, requestHash, responseBody);

            return response;

        } catch (RuntimeException exception) {
            releaseRedisLock(idempotencyKey);
            throw exception;
        }
    }

    private void setRedisInFlightLock(String idempotencyKey, String requestHash) {
        if (redisIdempotencyGateway != null) {
            try {
                redisIdempotencyGateway.setIfAbsent(
                        idempotencyKey,
                        "PROCESSING:" + requestHash,
                        LOCK_TTL
                );
            } catch (Exception exception) {
                log.warn("Failed to set Redis in-flight lock: {}", exception.getMessage());
            }
        }
    }

    private void populateRedisCache(String idempotencyKey, String requestHash, String responseBody) {
        if (redisIdempotencyGateway != null) {
            try {
                redisIdempotencyGateway.set(
                        idempotencyKey,
                        "COMPLETED:" + requestHash + ":" + responseBody,
                        RESPONSE_TTL
                );
            } catch (Exception exception) {
                log.warn("Failed to populate Redis cache: {}", exception.getMessage());
            }
        }
    }

    private void releaseRedisLock(String idempotencyKey) {
        if (redisIdempotencyGateway != null) {
            try {
                redisIdempotencyGateway.release(idempotencyKey);
            } catch (Exception exception) {
                log.warn("Failed to release Redis lock: {}", exception.getMessage());
            }
        }
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
