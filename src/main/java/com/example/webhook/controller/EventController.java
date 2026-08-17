package com.example.webhook.controller;

import com.example.webhook.dto.IngestEventRequest;
import com.example.webhook.dto.IngestEventResponse;
import com.example.webhook.service.EventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
@Slf4j
public class EventController {

    private final EventService eventService;

    /**
     * Ingest an event. Returns 202 Accepted immediately after persistence and delivery staging.
     * No outbound HTTP call is made before this response is returned.
     */
    @PostMapping
    public ResponseEntity<IngestEventResponse> ingest(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @Valid @RequestBody IngestEventRequest request) {
        IngestEventResponse response = eventService.ingest(tenantId, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
}
