package com.example.webhook.repository;

import com.example.webhook.entity.Endpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EndpointRepository extends JpaRepository<Endpoint, UUID> {

    List<Endpoint> findByTenantId(UUID tenantId);

    Optional<Endpoint> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * Find all ACTIVE endpoints for a given tenant that subscribe to the given event type.
     * Uses the PostgreSQL array operator @> to check if the array contains the event type.
     */
    @Query(value = """
        SELECT * FROM endpoints
        WHERE tenant_id = :tenantId
          AND status = 'ACTIVE'
          AND subscribed_event_types @> ARRAY[:eventType]::TEXT[]
        """, nativeQuery = true)
    List<Endpoint> findActiveSubscribersForEventType(
            @Param("tenantId") UUID tenantId,
            @Param("eventType") String eventType);
}
