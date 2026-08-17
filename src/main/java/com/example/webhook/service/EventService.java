package com.example.webhook.service;

import com.example.webhook.dto.IngestEventRequest;
import com.example.webhook.dto.IngestEventResponse;
import com.example.webhook.entity.Delivery;
import com.example.webhook.entity.Endpoint;
import com.example.webhook.entity.Event;
import com.example.webhook.entity.Tenant;
import com.example.webhook.exception.TenantNotFoundException;
import com.example.webhook.repository.DeliveryRepository;
import com.example.webhook.repository.EndpointRepository;
import com.example.webhook.repository.EventRepository;
import com.example.webhook.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class EventService {

    private final TenantRepository tenantRepository;
    private final EventRepository eventRepository;
    private final EndpointRepository endpointRepository;
    private final DeliveryRepository deliveryRepository;

    /**
     * Ingest an event with full idempotency and fan-out.
     *
     * Transaction covers:
     *   1. Tenant validation
     *   2. Idempotency check (DB unique constraint as safety net)
     *   3. Persist event
     *   4. Fan-out: find all active matching endpoint subscriptions
     *   5. Create one PENDING delivery per matching endpoint
     *   6. Commit
     *
     * CRITICAL: No outbound HTTP is ever made inside this transaction.
     * Background workers perform the actual delivery asynchronously.
     */
    @Transactional
    public IngestEventResponse ingest(String tenantName, IngestEventRequest request) {
        Tenant tenant = resolveTenant(tenantName);

        // Idempotency: check if we've already seen this eventId for this tenant
        // We rely on the DB unique constraint (tenant_id, event_id_external) as the true guard
        // against races — but we do a fast read first for the happy path.
        var existing = eventRepository.findByTenantIdAndEventIdExternal(
                tenant.getId(), request.getEventId());

        if (existing.isPresent()) {
            log.info("Idempotent re-submission: tenant={} eventId={} internalId={}",
                    tenantName, request.getEventId(), existing.get().getId());
            return IngestEventResponse.builder()
                    .eventId(existing.get().getId())
                    .externalEventId(existing.get().getEventIdExternal())
                    .status("ALREADY_ACCEPTED")
                    .createdAt(existing.get().getCreatedAt())
                    .deliveriesCreated(0)
                    .build();
        }

        // Persist the event
        Event event = new Event();
        event.setTenantId(tenant.getId());
        event.setEventIdExternal(request.getEventId());
        event.setType(request.getType());
        event.setPayload(request.getPayload());

        Event savedEvent;
        try {
            savedEvent = eventRepository.save(event);
            eventRepository.flush(); // flush so constraint violation surfaces here if race
        } catch (DataIntegrityViolationException ex) {
            // Race condition: another concurrent request beat us to it — return existing
            log.info("Concurrent duplicate event detected (constraint): tenant={} eventId={}",
                    tenantName, request.getEventId());
            Event race = eventRepository.findByTenantIdAndEventIdExternal(
                    tenant.getId(), request.getEventId())
                    .orElseThrow(() -> new IllegalStateException("Unexpected state after constraint violation"));
            return IngestEventResponse.builder()
                    .eventId(race.getId())
                    .externalEventId(race.getEventIdExternal())
                    .status("ALREADY_ACCEPTED")
                    .createdAt(race.getCreatedAt())
                    .deliveriesCreated(0)
                    .build();
        }

        // Fan-out: find all ACTIVE endpoints for this tenant subscribed to this event type
        List<Endpoint> subscribers = endpointRepository
                .findActiveSubscribersForEventType(tenant.getId(), request.getType());

        List<Delivery> deliveries = new ArrayList<>();
        for (Endpoint ep : subscribers) {
            Delivery delivery = new Delivery();
            delivery.setEventId(savedEvent.getId());
            delivery.setEndpointId(ep.getId());
            delivery.setTenantId(tenant.getId());
            delivery.setStatus(Delivery.DeliveryStatus.PENDING);
            delivery.setAttemptCount(0);
            delivery.setNextAttemptAt(OffsetDateTime.now());
            deliveries.add(delivery);
        }

        List<Delivery> savedDeliveries = deliveryRepository.saveAll(deliveries);

        log.info("Event ingested: tenant={} eventId={} internalId={} type={} deliveriesCreated={}",
                tenantName, request.getEventId(), savedEvent.getId(),
                request.getType(), savedDeliveries.size());

        return IngestEventResponse.builder()
                .eventId(savedEvent.getId())
                .externalEventId(savedEvent.getEventIdExternal())
                .status("ACCEPTED")
                .createdAt(savedEvent.getCreatedAt())
                .deliveriesCreated(savedDeliveries.size())
                .build();
    }

    private Tenant resolveTenant(String tenantName) {
        return tenantRepository.findByName(tenantName.toLowerCase())
                .orElseThrow(() -> new TenantNotFoundException("Unknown tenant: " + tenantName));
    }
}
