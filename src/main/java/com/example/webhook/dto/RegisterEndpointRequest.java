package com.example.webhook.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class RegisterEndpointRequest {

    @NotBlank(message = "URL is required")
    private String url;

    @NotEmpty(message = "At least one event type is required")
    private List<@NotBlank String> eventTypes;

    private Boolean allowInternal;
}
