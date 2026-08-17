package com.example.webhook.controller;

import com.example.webhook.dto.EndpointResponse;
import com.example.webhook.dto.RegisterEndpointRequest;
import com.example.webhook.dto.TestEndpointResponse;
import com.example.webhook.security.TenantContext;
import com.example.webhook.service.EndpointService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/endpoints")
@RequiredArgsConstructor
@Slf4j
public class EndpointController {

    private final EndpointService endpointService;

    @PostMapping
    public ResponseEntity<EndpointResponse> register(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @Valid @RequestBody RegisterEndpointRequest request) {
        EndpointResponse response = endpointService.register(tenantId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<EndpointResponse>> list(
            @RequestHeader("X-Tenant-Id") String tenantId) {
        return ResponseEntity.ok(endpointService.listForTenant(tenantId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<EndpointResponse> getById(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(endpointService.getById(tenantId, id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> disable(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable UUID id) {
        endpointService.disable(tenantId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<TestEndpointResponse> test(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(endpointService.test(tenantId, id));
    }
}
