package com.example.webhook.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class EndpointResponse {
    private UUID id;
    private UUID tenantId;
    private String url;
    private List<String> eventTypes;
    private String status;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    // NOTE: secret is NOT included in responses — never expose signing secrets via API
}
