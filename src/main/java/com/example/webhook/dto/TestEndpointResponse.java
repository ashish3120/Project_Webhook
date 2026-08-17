package com.example.webhook.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TestEndpointResponse {
    private boolean reachable;
    private boolean success;
    private Integer responseCode;
    private String responseSnippet;
    private Long latencyMs;
    private String error;
}
