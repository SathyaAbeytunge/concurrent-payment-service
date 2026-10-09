
package com.sathya.payment.repository;

import com.sathya.payment.entity.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;

public interface IdempotencyKeyRepository
        extends JpaRepository<IdempotencyKey, String> {

    @Modifying
    @Query(value = """
            INSERT INTO idempotency_keys
                (key, request_hash, status, expires_at)
            VALUES
                (:key, :requestHash,
                 CAST('PROCESSING' AS idempotency_status),
                 :expiresAt)
            ON CONFLICT (key) DO NOTHING
            """, nativeQuery = true)
    int tryCreate(
            @Param("key") String key,
            @Param("requestHash") String requestHash,
            @Param("expiresAt") OffsetDateTime expiresAt
    );
}
