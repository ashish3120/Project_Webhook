package com.example.webhook.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class DeliveryResponse {
    private UUID id;
    private UUID eventId;
    private UUID endpointId;
    private String endpointUrl;
    private String status;
    private int attemptCount;
    private Integer lastResponseCode;
    private String lastResponseSnippet;
    private OffsetDateTime nextAttemptAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private List<DeliveryAttemptResponse> attempts;
}
