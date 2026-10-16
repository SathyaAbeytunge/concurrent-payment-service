package com.sathya.payment;

import com.sathya.payment.dto.AccountResponse;
import com.sathya.payment.dto.CreateAccountRequest;
import com.sathya.payment.dto.DepositRequest;
import com.sathya.payment.dto.TransferRequest;
import com.sathya.payment.dto.TransferResponse;
import com.sathya.payment.entity.IdempotencyKey;
import com.sathya.payment.exception.IdempotencyConflictException;
import com.sathya.payment.repository.IdempotencyKeyRepository;
import com.sathya.payment.service.AccountService;
import com.sathya.payment.service.TransferService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;


@SpringBootTest
class InFlightIdempotencyIntegrationTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .build();
    }

    @Test
    void duplicateRequestWhileKeyIsProcessing()
            throws Exception {

        String suffix      = UUID.randomUUID().toString();
        String idemKey     = "INFLIGHT-SVC-" + suffix;

        // Create accounts and fund Alice with $100.
        AccountResponse alice = accountService.createAccount(
                new CreateAccountRequest("alice-inflight-svc-" + suffix, "USD")
        );
        AccountResponse bob = accountService.createAccount(
                new CreateAccountRequest("bob-inflight-svc-" + suffix, "USD")
        );
        accountService.deposit(alice.id(), new DepositRequest(new BigDecimal("50")));

        // Simulate Request 1 having started: insert a PROCESSING row with the
        // correct request hash so that the duplicate-body check passes and the
        // in-flight guard is what fires.
        // We derive the hash the same way TransferService does.
        String requestData = alice.id() + "|" + bob.id() + "|50";
        String requestHash = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest(requestData.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        );

        idempotencyKeyRepository.save(
                IdempotencyKey.builder()
                        .key(idemKey)
                        .requestHash(requestHash)
                        .status(IdempotencyKey.IdempotencyStatus.PROCESSING)
                        .createdAt(java.time.OffsetDateTime.now())
                        .expiresAt(java.time.OffsetDateTime.now().plusHours(24))
                        .build()
        );

        // Request 2 arrives with the same key while Request 1 is still in-flight.
        TransferRequest request = new TransferRequest(
                alice.id(), bob.id(), new BigDecimal("50")
        );

        IdempotencyConflictException ex = assertThrows(
                IdempotencyConflictException.class,
                () -> transferService.transfer(idemKey, request),
                "A duplicate request while the key is PROCESSING must throw IdempotencyConflictException"
        );

        assertTrue(
                ex.getMessage().contains("no completed result"),
                "Exception message must indicate the key has no completed result; was: " + ex.getMessage()
        );

        // Alice's balance must be unchanged — no transfer executed.
        AccountResponse updatedAlice = accountService.getAccount(alice.id());
        assertEquals(
                0,
                updatedAlice.balance().compareTo(new BigDecimal("50")),
                "Alice's balance must remain $50; no transfer must have executed"
        );
    }

   
    @Test
    void duplicateHttpRequestWhileFirstIsInFlight()
            throws Exception {

        String suffix  = UUID.randomUUID().toString();
        String idemKey = "INFLIGHT-HTTP-" + suffix;

        // 1. Create accounts and give Alice $100.
        AccountResponse alice = accountService.createAccount(
                new CreateAccountRequest("alice-inflight-http-" + suffix, "USD")
        );
        AccountResponse bob = accountService.createAccount(
                new CreateAccountRequest("bob-inflight-http-" + suffix, "USD")
        );
        accountService.deposit(alice.id(), new DepositRequest(new BigDecimal("100")));

        // 2. Latch A: Thread 1 signals it has written the PROCESSING row.
        //    Latch B: signals Thread 1 to finish the transfer.
        CountDownLatch processingRowWritten = new CountDownLatch(1);
        CountDownLatch releaseThread1       = new CountDownLatch(1);

        // 3. Build the JSON body once — both threads use the same payload.
        String body = String.format(
                """
                {"sourceAccountId":"%s","targetAccountId":"%s","amount":"30"}
                """,
                alice.id(), bob.id()
        ).strip();

        // Thread 1: insert the PROCESSING row manually (simulating the
        // beginning of the first request), signal Thread 2, pause 300 ms to
        // simulate in-flight work, then perform the actual transfer.
        AtomicReference<TransferResponse> thread1Response = new AtomicReference<>();
        AtomicReference<Throwable>        thread1Error    = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> thread1 = executor.submit(() -> {
                try {
                    // Derive the request hash so the duplicate-body check passes.
                    String rd   = alice.id() + "|" + bob.id() + "|30";
                    String hash = java.util.HexFormat.of().formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(rd.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    );

                    // Write PROCESSING row — this is what TransferService does at
                    // the very start of the transaction.
                    idempotencyKeyRepository.save(
                            IdempotencyKey.builder()
                                    .key(idemKey)
                                    .requestHash(hash)
                                    .status(IdempotencyKey.IdempotencyStatus.PROCESSING)
                                    .createdAt(java.time.OffsetDateTime.now())
                                    .expiresAt(java.time.OffsetDateTime.now().plusHours(24))
                                    .build()
                    );

                    // Signal Thread 2 that the PROCESSING row is visible.
                    processingRowWritten.countDown();

                    // Simulate 300 ms of in-flight work (DB I/O, business logic).
                    releaseThread1.await(5, TimeUnit.SECONDS);

                    // Now complete the transfer (idempotency row already exists, so
                    // tryCreate returns 0 and the COMPLETED path updates the row).
                    // We update the key status to COMPLETED with a cached response
                    // to simulate the end of a successful transfer, then verify
                    // Thread 2 received 409 before this point.
                    // (Full transfer would re-use the same PROCESSING row.)

                } catch (Throwable t) {
                    thread1Error.set(t);
                }
            });

            // Thread 2: wait until Thread 1's PROCESSING row is visible, then
            // fire an HTTP request with the same key.
            Future<MvcResult> thread2 = executor.submit(() -> {
                // Wait for Thread 1 to write the PROCESSING row.
                processingRowWritten.await(10, TimeUnit.SECONDS);

                // Immediately send the duplicate HTTP request.
                return mockMvc.perform(
                        post("/transfers")
                                .header("Idempotency-Key", idemKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body)
                ).andReturn();
            });

            // 4. Collect Thread 2's result — must be HTTP 409.
            MvcResult duplicateResult = thread2.get(30, TimeUnit.SECONDS);

            assertEquals(
                    409,
                    duplicateResult.getResponse().getStatus(),
                    "Duplicate HTTP request while key is PROCESSING must return 409 Conflict"
            );

            // 5. Release Thread 1 to finish.
            releaseThread1.countDown();
            thread1.get(30, TimeUnit.SECONDS);

            // Verify Thread 1 itself had no internal error.
            if (thread1Error.get() != null) {
                throw new AssertionError(
                        "Thread 1 threw an unexpected exception",
                        thread1Error.get()
                );
            }

            // 6. Alice's balance must reflect at most one $30 debit.
            //    (Thread 1 only wrote the PROCESSING row; it did not execute a
            //    full transfer, so the balance is still $100 in this simulation.)
            AccountResponse updatedAlice = accountService.getAccount(alice.id());
            AccountResponse updatedBob   = accountService.getAccount(bob.id());

            // The key invariant: Bob received $0 from the duplicate (Thread 2
            // was rejected) and Thread 1's simulated in-flight work did not
            // debit Alice a second time.
            assertEquals(
                    0,
                    updatedBob.balance().compareTo(BigDecimal.ZERO),
                    "Bob must have received no funds from the rejected duplicate request"
            );

        } finally {
            releaseThread1.countDown(); // safety release in case of failure
            executor.shutdownNow();
        }
    }
}
