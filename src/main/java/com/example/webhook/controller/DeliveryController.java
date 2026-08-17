package com.example.webhook.controller;

import com.example.webhook.dto.DeliveryResponse;
import com.example.webhook.entity.Delivery;
import com.example.webhook.service.DeliveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
public class DeliveryController {

    private final DeliveryService deliveryService;

    /**
     * Get all deliveries for a specific event (with attempt history).
     * Tenant isolation: only returns deliveries for the requesting tenant's event.
     */
    @GetMapping("/events/{eventId}/deliveries")
    public ResponseEntity<List<DeliveryResponse>> getDeliveriesForEvent(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable UUID eventId) {
        return ResponseEntity.ok(deliveryService.getDeliveriesForEvent(tenantId, eventId));
    }

    /**
     * Get paginated deliveries for an endpoint, with optional filtering.
     * All filtering is done at the database level — no in-memory filtering.
     */
    @GetMapping("/endpoints/{endpointId}/deliveries")
    public ResponseEntity<Page<DeliveryResponse>> getDeliveriesForEndpoint(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable UUID endpointId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Delivery.DeliveryStatus deliveryStatus = status != null
                ? Delivery.DeliveryStatus.valueOf(status.toUpperCase())
                : null;

        return ResponseEntity.ok(deliveryService.getDeliveriesForEndpoint(
                tenantId, endpointId, deliveryStatus, from, to, page, size));
    }

    /**
     * Manual redrive — re-queue a DEAD_LETTERED delivery.
     * Only the owning tenant can redrive their own delivery.
     */
    @PostMapping("/deliveries/{deliveryId}/redrive")
    public ResponseEntity<DeliveryResponse> redrive(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable UUID deliveryId) {
        return ResponseEntity.ok(deliveryService.redrive(tenantId, deliveryId));
    }
}
