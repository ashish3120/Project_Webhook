package com.example.webhook.service;

import com.example.webhook.dto.DeliveryAttemptResponse;
import com.example.webhook.dto.DeliveryResponse;
import com.example.webhook.entity.Delivery;
import com.example.webhook.entity.DeliveryAttempt;
import com.example.webhook.entity.Endpoint;
import com.example.webhook.entity.Tenant;
import com.example.webhook.exception.DeliveryNotFoundException;
import com.example.webhook.exception.EndpointNotFoundException;
import com.example.webhook.exception.TenantNotFoundException;
import com.example.webhook.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeliveryService {

    private final TenantRepository tenantRepository;
    private final DeliveryRepository deliveryRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final EndpointRepository endpointRepository;
    private final EventRepository eventRepository;

    @Transactional(readOnly = true)
    public List<DeliveryResponse> getDeliveriesForEvent(String tenantName, UUID eventId) {
        Tenant tenant = resolveTenant(tenantName);
        // Verify event belongs to this tenant
        eventRepository.findByIdAndTenantId(eventId, tenant.getId())
                .orElseThrow(() -> new EndpointNotFoundException("Event not found: " + eventId));

        return deliveryRepository.findByEventIdAndTenantId(eventId, tenant.getId())
                .stream()
                .map(d -> toResponse(d, true))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<DeliveryResponse> getDeliveriesForEndpoint(
            String tenantName, UUID endpointId,
            Delivery.DeliveryStatus status,
            OffsetDateTime from, OffsetDateTime to,
            int page, int size) {

        Tenant tenant = resolveTenant(tenantName);
        // Verify endpoint belongs to this tenant
        endpointRepository.findByIdAndTenantId(endpointId, tenant.getId())
                .orElseThrow(() -> new EndpointNotFoundException("Endpoint not found: " + endpointId));

        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());

        Page<Delivery> deliveries;
        if (status != null && from != null && to != null) {
            deliveries = deliveryRepository.findByEndpointIdAndTenantIdAndStatusAndCreatedAtBetween(
                    endpointId, tenant.getId(), status, from, to, pageable);
        } else if (status != null) {
            deliveries = deliveryRepository.findByEndpointIdAndTenantIdAndStatus(
                    endpointId, tenant.getId(), status, pageable);
        } else if (from != null && to != null) {
            deliveries = deliveryRepository.findByEndpointIdAndTenantIdAndCreatedAtBetween(
                    endpointId, tenant.getId(), from, to, pageable);
        } else {
            deliveries = deliveryRepository.findByEndpointIdAndTenantId(
                    endpointId, tenant.getId(), pageable);
        }

        return deliveries.map(d -> toResponse(d, false));
    }

    /**
     * Manual redrive: re-queue a DEAD_LETTERED delivery back to PENDING.
     * Only the owning tenant can redrive.
     * Previous attempt history is preserved (immutable).
     */
    @Transactional
    public DeliveryResponse redrive(String tenantName, UUID deliveryId) {
        Tenant tenant = resolveTenant(tenantName);
        Delivery delivery = deliveryRepository.findByIdAndTenantId(deliveryId, tenant.getId())
                .orElseThrow(() -> new DeliveryNotFoundException(
                    "Delivery not found: " + deliveryId + " for tenant: " + tenantName));

        if (delivery.getStatus() != Delivery.DeliveryStatus.DEAD_LETTERED) {
            throw new IllegalStateException(
                "Delivery " + deliveryId + " is not DEAD_LETTERED (current status: " + delivery.getStatus() + ")");
        }

        // Reset to PENDING — preserve attempt count but reset scheduling
        delivery.setStatus(Delivery.DeliveryStatus.PENDING);
        delivery.setNextAttemptAt(OffsetDateTime.now());
        delivery.setLockedBy(null);
        delivery.setLockedUntil(null);
        delivery.setUpdatedAt(OffsetDateTime.now());
        // Note: attemptCount is intentionally preserved so we can track total attempts across redrives

        Delivery saved = deliveryRepository.save(delivery);
        log.info("Redrived delivery id={} tenant={}", deliveryId, tenantName);
        return toResponse(saved, true);
    }

    private DeliveryResponse toResponse(Delivery delivery, boolean includeAttempts) {
        String endpointUrl = null;
        try {
            var ep = endpointRepository.findById(delivery.getEndpointId());
            endpointUrl = ep.map(Endpoint::getUrl).orElse("[deleted]");
        } catch (Exception e) {
            endpointUrl = "[unknown]";
        }

        List<DeliveryAttemptResponse> attempts = null;
        if (includeAttempts) {
            attempts = deliveryAttemptRepository
                    .findByDeliveryIdOrderByAttemptNumberAsc(delivery.getId())
                    .stream()
                    .map(this::toAttemptResponse)
                    .collect(Collectors.toList());
        }

        return DeliveryResponse.builder()
                .id(delivery.getId())
                .eventId(delivery.getEventId())
                .endpointId(delivery.getEndpointId())
                .endpointUrl(endpointUrl)
                .status(delivery.getStatus().name())
                .attemptCount(delivery.getAttemptCount())
                .lastResponseCode(delivery.getLastResponseCode())
                .lastResponseSnippet(delivery.getLastResponseSnippet())
                .nextAttemptAt(delivery.getNextAttemptAt())
                .createdAt(delivery.getCreatedAt())
                .updatedAt(delivery.getUpdatedAt())
                .attempts(attempts)
                .build();
    }

    private DeliveryAttemptResponse toAttemptResponse(DeliveryAttempt attempt) {
        return DeliveryAttemptResponse.builder()
                .id(attempt.getId())
                .attemptNumber(attempt.getAttemptNumber())
                .responseCode(attempt.getResponseCode())
                .latencyMs(attempt.getLatencyMs())
                .error(attempt.getError())
                .responseSnippet(attempt.getResponseSnippet())
                .createdAt(attempt.getCreatedAt())
                .build();
    }

    private Tenant resolveTenant(String tenantName) {
        return tenantRepository.findByName(tenantName.toLowerCase())
                .orElseThrow(() -> new TenantNotFoundException("Unknown tenant: " + tenantName));
    }
}
