package com.example.webhook.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class DeliveryAttemptResponse {
    private UUID id;
    private int attemptNumber;
    private Integer responseCode;
    private Long latencyMs;
    private String error;
    private String responseSnippet;
    private OffsetDateTime createdAt;
}
