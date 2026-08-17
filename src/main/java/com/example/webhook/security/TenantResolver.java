package com.example.webhook.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Servlet filter that:
 * 1. Assigns/propagates a Correlation ID (UUID) via MDC for structured logging
 * 2. Sets the current tenant name from the X-Tenant-Id header in TenantContext
 * 3. Clears both at the end of each request
 */
@Component
@Order(1)
public class TenantResolver implements Filter {

    public static final String TENANT_HEADER = "X-Tenant-Id";
    public static final String CORRELATION_HEADER = "X-Correlation-Id";
    public static final String MDC_CORRELATION_KEY = "correlationId";
    public static final String MDC_TENANT_KEY = "tenantId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpReq = (HttpServletRequest) request;
        HttpServletResponse httpResp = (HttpServletResponse) response;

        // Generate or propagate correlation ID
        String correlationId = httpReq.getHeader(CORRELATION_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_CORRELATION_KEY, correlationId);
        httpResp.setHeader(CORRELATION_HEADER, correlationId);

        // Set tenant from header
        String tenantName = httpReq.getHeader(TENANT_HEADER);
        if (tenantName != null && !tenantName.isBlank()) {
            TenantContext.setCurrentTenant(tenantName.trim().toLowerCase());
            MDC.put(MDC_TENANT_KEY, tenantName);
        }

        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            MDC.remove(MDC_CORRELATION_KEY);
            MDC.remove(MDC_TENANT_KEY);
        }
    }
}
