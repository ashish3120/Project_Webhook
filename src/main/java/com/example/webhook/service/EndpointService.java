package com.example.webhook.service;

import com.example.webhook.dto.EndpointResponse;
import com.example.webhook.dto.RegisterEndpointRequest;
import com.example.webhook.dto.TestEndpointResponse;
import com.example.webhook.entity.Delivery;
import com.example.webhook.entity.Endpoint;
import com.example.webhook.entity.Tenant;
import com.example.webhook.exception.EndpointNotFoundException;
import com.example.webhook.exception.TenantNotFoundException;
import com.example.webhook.repository.EndpointRepository;
import com.example.webhook.repository.TenantRepository;
import com.example.webhook.util.SecretGenerator;
import com.example.webhook.util.UrlValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class EndpointService {

    private final TenantRepository tenantRepository;
    private final EndpointRepository endpointRepository;
    private final UrlValidator urlValidator;
    private final SecretGenerator secretGenerator;
    private final WebhookClient webhookClient;

    @Transactional
    public EndpointResponse register(String tenantName, RegisterEndpointRequest request) {
        Tenant tenant = resolveTenant(tenantName);
        boolean allowInternal = Boolean.TRUE.equals(request.getAllowInternal());
        urlValidator.validate(request.getUrl(), allowInternal);

        String secret = secretGenerator.generate();

        Endpoint endpoint = new Endpoint();
        endpoint.setTenantId(tenant.getId());
        endpoint.setUrl(request.getUrl());
        endpoint.setSecret(secret);
        endpoint.setSubscribedEventTypes(request.getEventTypes());
        endpoint.setStatus(Endpoint.EndpointStatus.ACTIVE);
        endpoint.setAllowInternal(allowInternal);

        Endpoint saved = endpointRepository.save(endpoint);
        log.info("Registered endpoint id={} tenant={} url={}", saved.getId(), tenantName, saved.getUrl());
        // IMPORTANT: secret is logged only at TRACE level — never at INFO or above
        log.trace("Endpoint secret generated for id={} (NOT logged at INFO)", saved.getId());

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<EndpointResponse> listForTenant(String tenantName) {
        Tenant tenant = resolveTenant(tenantName);
        return endpointRepository.findByTenantId(tenant.getId())
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public EndpointResponse getById(String tenantName, UUID endpointId) {
        Tenant tenant = resolveTenant(tenantName);
        Endpoint ep = endpointRepository.findByIdAndTenantId(endpointId, tenant.getId())
                .orElseThrow(() -> new EndpointNotFoundException(
                    "Endpoint not found: " + endpointId + " for tenant: " + tenantName));
        return toResponse(ep);
    }

    /**
     * Soft-delete: disable the endpoint without destroying delivery history.
     */
    @Transactional
    public void disable(String tenantName, UUID endpointId) {
        Tenant tenant = resolveTenant(tenantName);
        Endpoint ep = endpointRepository.findByIdAndTenantId(endpointId, tenant.getId())
                .orElseThrow(() -> new EndpointNotFoundException(
                    "Endpoint not found: " + endpointId + " for tenant: " + tenantName));
        ep.setStatus(Endpoint.EndpointStatus.DISABLED);
        ep.setUpdatedAt(OffsetDateTime.now());
        endpointRepository.save(ep);
        log.info("Disabled endpoint id={} tenant={}", endpointId, tenantName);
    }

    /**
     * Send a synthetic test event to verify endpoint reachability.
     */
    @Transactional(readOnly = true)
    public TestEndpointResponse test(String tenantName, UUID endpointId) {
        Tenant tenant = resolveTenant(tenantName);
        Endpoint ep = endpointRepository.findByIdAndTenantId(endpointId, tenant.getId())
                .orElseThrow(() -> new EndpointNotFoundException(
                    "Endpoint not found: " + endpointId + " for tenant: " + tenantName));
        return webhookClient.sendTestPing(ep);
    }

    private Tenant resolveTenant(String tenantName) {
        return tenantRepository.findByName(tenantName.toLowerCase())
                .orElseThrow(() -> new TenantNotFoundException("Unknown tenant: " + tenantName));
    }

    private EndpointResponse toResponse(Endpoint ep) {
        return EndpointResponse.builder()
                .id(ep.getId())
                .tenantId(ep.getTenantId())
                .url(ep.getUrl())
                .eventTypes(ep.getSubscribedEventTypes())
                .status(ep.getStatus().name())
                .createdAt(ep.getCreatedAt())
                .updatedAt(ep.getUpdatedAt())
                .build();
    }
}
