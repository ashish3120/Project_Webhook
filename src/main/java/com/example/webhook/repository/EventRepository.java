package com.example.webhook.repository;

import com.example.webhook.entity.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EventRepository extends JpaRepository<Event, UUID> {

    Optional<Event> findByTenantIdAndEventIdExternal(UUID tenantId, String eventIdExternal);

    Optional<Event> findByIdAndTenantId(UUID id, UUID tenantId);
}
