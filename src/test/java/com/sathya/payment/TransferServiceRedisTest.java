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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
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
import static org.mockito.Mockito.doThrow;
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
    private TestTransactionManager transactionManager;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionManager = new TestTransactionManager();
        transactionManager.setRollbackOnCommitFailure(true);
        transactionTemplate = new TransactionTemplate(transactionManager);
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
    void shouldCacheCompletedResponseOnlyAfterCommit() throws Exception {
        String idemKey = "IDEM-REDIS-SUCCESS";
        TransferRequest request = prepareSuccessfulTransfer(idemKey);
        String hash = calculateHash(request.sourceAccountId(), request.targetAccountId(), request.amount());

        TransferResponse response = transactionTemplate.execute(status -> {
            TransferResponse result = transferService.transfer(idemKey, request);
            verify(redisIdempotencyGateway, never()).set(any(), any(), any());
            return result;
        });

        assertNotNull(response);
        verify(redisIdempotencyGateway).setIfAbsent(eq(idemKey), eq("PROCESSING:" + hash), eq(Duration.ofMinutes(5)));
        verify(redisIdempotencyGateway).set(eq(idemKey), contains("COMPLETED:" + hash), eq(Duration.ofHours(24)));
        verify(redisIdempotencyGateway, never()).release(any());
    }

    @Test
    void shouldCleanUpWithoutCachingWhenCommitFails() throws Exception {
        String idemKey = "IDEM-COMMIT-FAIL";
        TransferRequest request = prepareSuccessfulTransfer(idemKey);
        transactionManager.failCommit = true;

        assertThrows(TransactionSystemException.class, () -> transactionTemplate.execute(status ->
                transferService.transfer(idemKey, request)));

        verify(redisIdempotencyGateway, never()).set(any(), any(), any());
        verify(redisIdempotencyGateway).release(idemKey);
    }

    @Test
    void shouldCleanUpWithoutCachingWhenOuterTransactionRollsBack() throws Exception {
        String idemKey = "IDEM-OUTER-ROLLBACK";
        TransferRequest request = prepareSuccessfulTransfer(idemKey);

        transactionTemplate.executeWithoutResult(status -> {
            transferService.transfer(idemKey, request);
            status.setRollbackOnly();
            verify(redisIdempotencyGateway, never()).release(any());
        });

        verify(redisIdempotencyGateway, never()).set(any(), any(), any());
        verify(redisIdempotencyGateway).release(idemKey);
    }

    @Test
    void shouldReturnCommittedResponseWhenRedisCacheWriteFails() throws Exception {
        String idemKey = "IDEM-CACHE-FAIL";
        TransferRequest request = prepareSuccessfulTransfer(idemKey);
        doThrow(new RuntimeException("Redis unavailable"))
                .when(redisIdempotencyGateway).set(eq(idemKey), any(), any());

        TransferResponse response = transactionTemplate.execute(status -> transferService.transfer(idemKey, request));

        assertNotNull(response);
        verify(redisIdempotencyGateway, never()).release(any());
    }

    private TransferRequest prepareSuccessfulTransfer(String idemKey) throws Exception {
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("10.00");
        String hash = calculateHash(sourceId, targetId, amount);

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

        return new TransferRequest(sourceId, targetId, amount);
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
        assertThrows(InsufficientFundsException.class, () -> transactionTemplate.execute(status -> {
            try {
                return transferService.transfer(idemKey, request);
            } finally {
                verify(redisIdempotencyGateway, never()).release(any());
            }
        }));

        verify(redisIdempotencyGateway).release(idemKey);
    }

    private static class TestTransactionManager extends AbstractPlatformTransactionManager {
        private boolean failCommit;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            if (failCommit) {
                throw new TransactionSystemException("Commit failed");
            }
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
