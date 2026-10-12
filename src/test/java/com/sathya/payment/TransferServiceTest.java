package com.sathya.payment;

import com.sathya.payment.dto.TransferRequest;
import com.sathya.payment.exception.InvalidTransferException;
import com.sathya.payment.repository.AccountRepository;
import com.sathya.payment.repository.IdempotencyKeyRepository;
import com.sathya.payment.repository.LedgerEntryRepository;
import com.sathya.payment.repository.TransactionRepository;
import com.sathya.payment.service.TransferService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import com.sathya.payment.entity.Account;
import com.sathya.payment.entity.Transaction;
import com.sathya.payment.exception.InsufficientFundsException;

import java.math.BigDecimal;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sathya.payment.entity.IdempotencyKey;
import com.sathya.payment.dto.TransferResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;

import com.sathya.payment.exception.IdempotencyConflictException;
import com.sathya.payment.exception.AccountNotFoundException;
import com.sathya.payment.exception.InvalidTransferException;

import static org.mockito.Mockito.times;

class TransferServiceTest {

    @Test
    void shouldRejectBlankIdempotencyKey() {
        TransferService service = new TransferService(
                null,
                null,
                null,
                null,
                null
        );

        TransferRequest request = new TransferRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new java.math.BigDecimal("10.00")
        );

        InvalidTransferException exception = assertThrows(
                InvalidTransferException.class,
                () -> service.transfer("   ", request)
        );

        assertEquals(
                "Idempotency-Key must contain 1 to 128 characters",
                exception.getMessage()
        );
    }

    
    @Test
    void shouldRejectIdempotencyKeyLongerThan128Characters() {
        TransferService service = new TransferService(
                null,
                null,
                null,
                null,
                null
        );

        TransferRequest request = new TransferRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new java.math.BigDecimal("10.00")
        );

        String longKey = "A".repeat(129);

        InvalidTransferException exception = assertThrows(
                InvalidTransferException.class,
                () -> service.transfer(longKey, request)
        );

        assertEquals(
                "Idempotency-Key must contain 1 to 128 characters",
                exception.getMessage()
        );
    }

    
    @Test
    void shouldReturnSavedResponseWhenSameKeyIsRetried() throws Exception {
        AccountRepository accountRepository =
                mock(AccountRepository.class);
        TransactionRepository transactionRepository =
                mock(TransactionRepository.class);
        LedgerEntryRepository ledgerEntryRepository =
                mock(LedgerEntryRepository.class);
        IdempotencyKeyRepository idempotencyKeyRepository =
                mock(IdempotencyKeyRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);

        TransferService service = new TransferService(
                accountRepository,
                transactionRepository,
                ledgerEntryRepository,
                idempotencyKeyRepository,
                objectMapper
        );

        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        TransferRequest request = new TransferRequest(
                sourceId,
                targetId,
                new java.math.BigDecimal("10.00")
        );

        String idempotencyKey = "PAY-001";

        String requestData = sourceId + "|"
                + targetId + "|10";

        String requestHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                        requestData.getBytes(StandardCharsets.UTF_8)
                )
        );

        IdempotencyKey existingKey = IdempotencyKey.builder()
                .key(idempotencyKey)
                .requestHash(requestHash)
                .status(IdempotencyKey.IdempotencyStatus.COMPLETED)
                .responseBody("saved-response")
                .build();

        when(idempotencyKeyRepository.tryCreate(
                eq(idempotencyKey),
                eq(requestHash),
                any(OffsetDateTime.class)
        )).thenReturn(0);

        when(idempotencyKeyRepository.findById(idempotencyKey))
                .thenReturn(Optional.of(existingKey));

        TransferResponse originalResponse = mock(TransferResponse.class);

        when(objectMapper.readValue(
                "saved-response",
                TransferResponse.class
        )).thenReturn(originalResponse);

        TransferResponse retryResponse = service.transfer(
                idempotencyKey,
                request
        );

        assertSame(originalResponse, retryResponse);
    }

    
    @Test
    void shouldRejectSameKeyWithDifferentRequest() {
        AccountRepository accountRepository =
                mock(AccountRepository.class);
        TransactionRepository transactionRepository =
                mock(TransactionRepository.class);
        LedgerEntryRepository ledgerEntryRepository =
                mock(LedgerEntryRepository.class);
        IdempotencyKeyRepository idempotencyKeyRepository =
                mock(IdempotencyKeyRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);

        TransferService service = new TransferService(
                accountRepository,
                transactionRepository,
                ledgerEntryRepository,
                idempotencyKeyRepository,
                objectMapper
        );

        TransferRequest request = new TransferRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new java.math.BigDecimal("20.00")
        );

        IdempotencyKey existingKey = IdempotencyKey.builder()
                .key("PAY-002")
                .requestHash("different-request-hash")
                .status(IdempotencyKey.IdempotencyStatus.COMPLETED)
                .responseBody("saved-response")
                .build();

        when(idempotencyKeyRepository.tryCreate(
                eq("PAY-002"),
                any(String.class),
                any(OffsetDateTime.class)
        )).thenReturn(0);

        when(idempotencyKeyRepository.findById("PAY-002"))
                .thenReturn(Optional.of(existingKey));

        assertThrows(
                IdempotencyConflictException.class,
                () -> service.transfer("PAY-002", request)
        );

        verify(accountRepository, never()).findByIdForUpdate(any());
    }

    
    @Test
    void shouldRejectTransferWhenFundsAreInsufficient() {
        AccountRepository accountRepository =
                mock(AccountRepository.class);
        TransactionRepository transactionRepository =
                mock(TransactionRepository.class);
        LedgerEntryRepository ledgerEntryRepository =
                mock(LedgerEntryRepository.class);
        IdempotencyKeyRepository idempotencyKeyRepository =
                mock(IdempotencyKeyRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);

        TransferService service = new TransferService(
                accountRepository,
                transactionRepository,
                ledgerEntryRepository,
                idempotencyKeyRepository,
                objectMapper
        );

        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        Account sourceAccount = Account.builder()
                .id(sourceId)
                .currency("USD")
                .balance(new BigDecimal("50.00"))
                .build();

        Account targetAccount = Account.builder()
                .id(targetId)
                .currency("USD")
                .balance(new BigDecimal("20.00"))
                .build();

        when(idempotencyKeyRepository.tryCreate(
                eq("PAY-INSUFFICIENT-001"),
                any(String.class),
                any(OffsetDateTime.class)
        )).thenReturn(1);

        when(accountRepository.findByIdForUpdate(sourceId))
                .thenReturn(Optional.of(sourceAccount));
        when(accountRepository.findByIdForUpdate(targetId))
                .thenReturn(Optional.of(targetAccount));

        TransferRequest request = new TransferRequest(
                sourceId,
                targetId,
                new BigDecimal("80.00")
        );

        assertThrows(
                InsufficientFundsException.class,
                () -> service.transfer("PAY-INSUFFICIENT-001", request)
        );

        assertEquals(new BigDecimal("50.00"), sourceAccount.getBalance());
        assertEquals(new BigDecimal("20.00"), targetAccount.getBalance());

        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void shouldRejectTransferToTheSameAccount() {
        AccountRepository accountRepository =
                mock(AccountRepository.class);
        TransactionRepository transactionRepository =
                mock(TransactionRepository.class);
        LedgerEntryRepository ledgerEntryRepository =
                mock(LedgerEntryRepository.class);
        IdempotencyKeyRepository idempotencyKeyRepository =
                mock(IdempotencyKeyRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);

        TransferService service = new TransferService(
                accountRepository,
                transactionRepository,
                ledgerEntryRepository,
                idempotencyKeyRepository,
                objectMapper
        );

        UUID accountId = UUID.randomUUID();

        when(idempotencyKeyRepository.tryCreate(
                eq("PAY-SELF-001"),
                any(String.class),
                any(OffsetDateTime.class)
        )).thenReturn(1);

        TransferRequest request = new TransferRequest(
                accountId,
                accountId,
                new BigDecimal("10.00")
        );

        assertThrows(
                InvalidTransferException.class,
                () -> service.transfer("PAY-SELF-001", request)
        );

        verify(accountRepository, never()).findByIdForUpdate(any());
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    
    @Test
    void shouldRejectTransferWhenSourceAccountDoesNotExist() {
        AccountRepository accountRepository =
                mock(AccountRepository.class);
        TransactionRepository transactionRepository =
                mock(TransactionRepository.class);
        LedgerEntryRepository ledgerEntryRepository =
                mock(LedgerEntryRepository.class);
        IdempotencyKeyRepository idempotencyKeyRepository =
                mock(IdempotencyKeyRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);

        TransferService service = new TransferService(
                accountRepository,
                transactionRepository,
                ledgerEntryRepository,
                idempotencyKeyRepository,
                objectMapper
        );

        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        when(idempotencyKeyRepository.tryCreate(
                eq("PAY-MISSING-001"),
                any(String.class),
                any(OffsetDateTime.class)
        )).thenReturn(1);

        when(accountRepository.findByIdForUpdate(sourceId))
                .thenReturn(Optional.empty());

        TransferRequest request = new TransferRequest(
                sourceId,
                targetId,
                new BigDecimal("10.00")
        );

        assertThrows(
                AccountNotFoundException.class,
                () -> service.transfer("PAY-MISSING-001", request)
        );

        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void shouldRejectTransferWhenCurrenciesDoNotMatch() {
        AccountRepository accountRepository =
                mock(AccountRepository.class);
        TransactionRepository transactionRepository =
                mock(TransactionRepository.class);
        LedgerEntryRepository ledgerEntryRepository =
                mock(LedgerEntryRepository.class);
        IdempotencyKeyRepository idempotencyKeyRepository =
                mock(IdempotencyKeyRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);

        TransferService service = new TransferService(
                accountRepository,
                transactionRepository,
                ledgerEntryRepository,
                idempotencyKeyRepository,
                objectMapper
        );

        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        Account sourceAccount = Account.builder()
                .id(sourceId)
                .currency("USD")
                .balance(new BigDecimal("100.00"))
                .build();

        Account targetAccount = Account.builder()
                .id(targetId)
                .currency("EUR")
                .balance(new BigDecimal("50.00"))
                .build();

        when(idempotencyKeyRepository.tryCreate(
                eq("PAY-CURRENCY-001"),
                any(String.class),
                any(OffsetDateTime.class)
        )).thenReturn(1);

        UUID firstId = sourceId.compareTo(targetId) < 0
                ? sourceId : targetId;
        UUID secondId = sourceId.compareTo(targetId) < 0
                ? targetId : sourceId;

        Account firstAccount = firstId.equals(sourceId)
                ? sourceAccount : targetAccount;
        Account secondAccount = secondId.equals(sourceId)
                ? sourceAccount : targetAccount;

        when(accountRepository.findByIdForUpdate(firstId))
                .thenReturn(Optional.of(firstAccount));
        when(accountRepository.findByIdForUpdate(secondId))
                .thenReturn(Optional.of(secondAccount));

        TransferRequest request = new TransferRequest(
                sourceId,
                targetId,
                new BigDecimal("10.00")
        );

        assertThrows(
                InvalidTransferException.class,
                () -> service.transfer("PAY-CURRENCY-001", request)
        );

        verify(transactionRepository, never()).save(any(Transaction.class));
    }




}