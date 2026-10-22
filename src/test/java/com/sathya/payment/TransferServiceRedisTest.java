package com.sathya.payment;

import com.sathya.payment.dto.TransferRequest;
import com.sathya.payment.dto.TransferResponse;
import com.sathya.payment.entity.Account;
import com.sathya.payment.entity.IdempotencyKey;
import com.sathya.payment.entity.Transaction;
import com.sathya.payment.exception.IdempotencyConflictException;
import com.sathya.payment.exception.InsufficientFundsException;
import com.sathya.payment.gateway.RedisIdempotencyGateway;
import com.sathya.payment.repository.AccountRepository;
import com.sathya.payment.repository.IdempotencyKeyRepository;
import com.sathya.payment.repository.LedgerEntryRepository;
import com.sathya.payment.repository.TransactionRepository;
import com.sathya.payment.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceRedisTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Mock
    private RedisIdempotencyGateway redisIdempotencyGateway;

    private ObjectMapper objectMapper;
    private TransferService transferService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        transferService = new TransferService(
                accountRepository,
                transactionRepository,
                ledgerEntryRepository,
                idempotencyKeyRepository,
                objectMapper,
                redisIdempotencyGateway
        );
    }

    private String calculateHash(UUID sourceId, UUID targetId, BigDecimal amount) throws Exception {
        String data = sourceId + "|" + targetId + "|" + amount.stripTrailingZeros().toPlainString();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(data.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void shouldReturnCachedResponseDirectlyFromRedis() throws Exception {
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("25.00");
        String hash = calculateHash(sourceId, targetId, amount);
        String idemKey = "IDEM-REDIS-CACHE";

        TransferResponse cachedResponse = new TransferResponse(
                UUID.randomUUID(), sourceId, targetId, amount, "USD",
                Transaction.TransactionStatus.SUCCESS, OffsetDateTime.now()
        );
        String json = objectMapper.writeValueAsString(cachedResponse);

        when(redisIdempotencyGateway.get(idemKey)).thenReturn("COMPLETED:" + hash + ":" + json);

        TransferRequest request = new TransferRequest(sourceId, targetId, amount);
        TransferResponse result = transferService.transfer(idemKey, request);

        assertNotNull(result);
        assertEquals(cachedResponse.transactionId(), result.transactionId());
        verify(idempotencyKeyRepository, never()).tryCreate(any(), any(), any());
    }

    @Test
    void shouldRejectConcurrentInFlightRequest() throws Exception {
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("25.00");
        String hash = calculateHash(sourceId, targetId, amount);
        String idemKey = "IDEM-REDIS-INFLIGHT";

        when(redisIdempotencyGateway.get(idemKey)).thenReturn("PROCESSING:" + hash);

        TransferRequest request = new TransferRequest(sourceId, targetId, amount);
        IdempotencyConflictException ex = assertThrows(
                IdempotencyConflictException.class,
                () -> transferService.transfer(idemKey, request)
        );

        assertEquals("This idempotency key has no completed result", ex.getMessage());
        verify(idempotencyKeyRepository, never()).tryCreate(any(), any(), any());
    }

    @Test
    void shouldRejectWhenCached() throws Exception {
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("25.00");
        String idemKey = "IDEM-REDIS-PAYLOAD-MISMATCH";

        when(redisIdempotencyGateway.get(idemKey)).thenReturn("PROCESSING:different-hash-value");

        TransferRequest request = new TransferRequest(sourceId, targetId, amount);
        IdempotencyConflictException ex = assertThrows(
                IdempotencyConflictException.class,
                () -> transferService.transfer(idemKey, request)
        );

        assertEquals("This idempotency key was already used for a different request", ex.getMessage());
    }

    @Test
    void shouldCacheCompletedResponse() throws Exception {
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("10.00");
        String hash = calculateHash(sourceId, targetId, amount);
        String idemKey = "IDEM-REDIS-SUCCESS";

        Account source = Account.builder().id(sourceId).currency("USD").balance(new BigDecimal("100.00")).build();
        Account target = Account.builder().id(targetId).currency("USD").balance(new BigDecimal("50.00")).build();

        when(redisIdempotencyGateway.get(idemKey)).thenReturn(null);
        when(idempotencyKeyRepository.tryCreate(eq(idemKey), eq(hash), any())).thenReturn(1);
        when(accountRepository.findByIdForUpdate(sourceId)).thenReturn(Optional.of(source));
        when(accountRepository.findByIdForUpdate(targetId)).thenReturn(Optional.of(target));
        when(transactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(idempotencyKeyRepository.findById(idemKey)).thenReturn(Optional.of(
                IdempotencyKey.builder().key(idemKey).requestHash(hash).build()
        ));

        TransferRequest request = new TransferRequest(sourceId, targetId, amount);
        TransferResponse response = transferService.transfer(idemKey, request);

        assertNotNull(response);
        verify(redisIdempotencyGateway).setIfAbsent(eq(idemKey), eq("PROCESSING:" + hash), eq(Duration.ofMinutes(5)));
        verify(redisIdempotencyGateway).set(eq(idemKey), contains("COMPLETED:" + hash), eq(Duration.ofHours(24)));
    }

    @Test
    void shouldReleaseRedisLockWhenTransferFails() throws Exception {
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("100.00");
        String hash = calculateHash(sourceId, targetId, amount);
        String idemKey = "IDEM-REDIS-FAIL";

        Account source = Account.builder().id(sourceId).currency("USD").balance(new BigDecimal("20.00")).build();
        Account target = Account.builder().id(targetId).currency("USD").balance(new BigDecimal("50.00")).build();

        when(redisIdempotencyGateway.get(idemKey)).thenReturn(null);
        when(idempotencyKeyRepository.tryCreate(eq(idemKey), eq(hash), any())).thenReturn(1);
        when(accountRepository.findByIdForUpdate(sourceId)).thenReturn(Optional.of(source));
        when(accountRepository.findByIdForUpdate(targetId)).thenReturn(Optional.of(target));

        TransferRequest request = new TransferRequest(sourceId, targetId, amount);
        assertThrows(InsufficientFundsException.class, () -> transferService.transfer(idemKey, request));

        verify(redisIdempotencyGateway).release(idemKey);
    }
}
