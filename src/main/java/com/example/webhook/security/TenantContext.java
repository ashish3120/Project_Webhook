package com.example.webhook.security;

/**
 * Thread-local store for the current tenant context.
 * Set by TenantResolver from the X-Tenant-Id header on every request.
 * Cleared at the end of each request to prevent leaks.
 */
public class TenantContext {

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {}

    public static void setCurrentTenant(String tenantName) {
        CURRENT_TENANT.set(tenantName);
    }

    public static String getCurrentTenant() {
        return CURRENT_TENANT.get();
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
