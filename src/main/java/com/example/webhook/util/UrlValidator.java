package com.example.webhook.util;

import com.example.webhook.exception.InvalidUrlException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.*;
import java.util.Set;

/**
 * Validates webhook endpoint URLs.
 *
 * Rules:
 * 1. Must be a valid URL parseable by java.net.URL
 * 2. Scheme must be http or https only (reject ftp://, file://, etc.)
 * 3. Localhost and private IP ranges are rejected UNLESS allowInternal=true or global flag is set
 *
 * Private IP ranges:
 *   10.0.0.0/8
 *   172.16.0.0/12
 *   192.168.0.0/16
 *   127.0.0.0/8 (loopback)
 */
@Component
public class UrlValidator {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    @Value("${webhook.allow-internal-endpoints:false}")
    private boolean globalAllowInternal;

    public void validate(String urlStr, boolean allowInternal) {
        URI uri;
        try {
            uri = new URI(urlStr);
        } catch (URISyntaxException e) {
            throw new InvalidUrlException("Invalid URL format: " + urlStr);
        }

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
            throw new InvalidUrlException("URL scheme must be http or https, got: " + scheme);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new InvalidUrlException("URL must have a valid host");
        }

        boolean internalAllowed = allowInternal || globalAllowInternal;
        if (!internalAllowed && isPrivateOrLocalhost(host)) {
            throw new InvalidUrlException(
                "Localhost and private IP range endpoints are not allowed. " +
                "Set allow_internal=true (development only) to override.");
        }
    }

    private boolean isPrivateOrLocalhost(String host) {
        // Check for localhost names
        String lower = host.toLowerCase();
        if (lower.equals("localhost") || lower.endsWith(".localhost") || lower.equals("127.0.0.1")) {
            return true;
        }

        try {
            InetAddress addr = InetAddress.getByName(host);
            return addr.isLoopbackAddress()
                || addr.isSiteLocalAddress()
                || addr.isLinkLocalAddress()
                || addr.isAnyLocalAddress();
        } catch (UnknownHostException e) {
            // If we can't resolve, allow it (DNS might not be available at registration time)
            return false;
        }
    }
}
