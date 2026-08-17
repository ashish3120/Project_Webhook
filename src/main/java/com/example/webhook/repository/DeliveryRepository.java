package com.example.webhook.repository;

import com.example.webhook.entity.Delivery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {

    Optional<Delivery> findByIdAndTenantId(UUID id, UUID tenantId);

    List<Delivery> findByEventIdAndTenantId(UUID eventId, UUID tenantId);

    Page<Delivery> findByEndpointIdAndTenantId(UUID endpointId, UUID tenantId, Pageable pageable);

    Page<Delivery> findByEndpointIdAndTenantIdAndStatus(
            UUID endpointId, UUID tenantId, Delivery.DeliveryStatus status, Pageable pageable);

    Page<Delivery> findByEndpointIdAndTenantIdAndCreatedAtBetween(
            UUID endpointId, UUID tenantId, OffsetDateTime from, OffsetDateTime to, Pageable pageable);

    Page<Delivery> findByEndpointIdAndTenantIdAndStatusAndCreatedAtBetween(
            UUID endpointId, UUID tenantId, Delivery.DeliveryStatus status,
            OffsetDateTime from, OffsetDateTime to, Pageable pageable);

    /**
     * Database-level due-delivery claiming using SELECT FOR UPDATE SKIP LOCKED.
     *
     * This is the core locking mechanism that ensures:
     * 1. Only PENDING deliveries past their next_attempt_at are selected
     * 2. Deliveries with valid (non-expired) leases are skipped
     * 3. Multiple concurrent workers cannot claim the same delivery (SKIP LOCKED)
     * 4. The row is immediately locked for update (no load-all-then-filter)
     */
    @Query(value = """
        SELECT * FROM deliveries
        WHERE status IN ('PENDING', 'IN_PROGRESS')
          AND next_attempt_at <= NOW()
          AND (locked_until IS NULL OR locked_until <= NOW())
        ORDER BY next_attempt_at
        FOR UPDATE SKIP LOCKED
        LIMIT :batchSize
        """, nativeQuery = true)
    List<Delivery> findDueDeliveriesForUpdate(@Param("batchSize") int batchSize);

    /**
     * Atomically claim a delivery by setting its lease.
     * Executed within its own transaction after the select.
     */
    @Modifying
    @Query("""
        UPDATE Delivery d SET
            d.status = :status,
            d.lockedBy = :lockedBy,
            d.lockedUntil = :lockedUntil,
            d.updatedAt = :now
        WHERE d.id = :id
        """)
    int claimDelivery(
            @Param("id") UUID id,
            @Param("status") Delivery.DeliveryStatus status,
            @Param("lockedBy") String lockedBy,
            @Param("lockedUntil") OffsetDateTime lockedUntil,
            @Param("now") OffsetDateTime now);
}
