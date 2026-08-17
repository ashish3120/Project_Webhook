package com.example.webhook.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class IngestEventResponse {
    private UUID eventId;
    private String externalEventId;
    private String status;
    private OffsetDateTime createdAt;
    private int deliveriesCreated;
}
