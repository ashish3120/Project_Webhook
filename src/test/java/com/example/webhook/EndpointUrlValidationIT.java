package com.example.webhook;

import com.example.webhook.dto.RegisterEndpointRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Endpoint URL Validation Tests
 *
 * The system must:
 * - Accept https:// and http:// URLs
 * - Reject ftp://, file://, etc.
 * - Reject localhost, 127.0.0.1, private IP ranges UNLESS allow_internal=true
 */
@DisplayName("Endpoint URL Validation Tests")
class EndpointUrlValidationIT extends BaseIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("Valid https:// URL is accepted")
    void validHttpsUrl_accepted() {
        var resp = registerEndpoint("https://valid.example.com/webhook", false);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("Valid http:// URL is accepted")
    void validHttpUrl_accepted() {
        var resp = registerEndpoint("http://valid2.example.com/webhook", false);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("ftp:// URL is rejected with 400")
    void ftpUrl_rejected() {
        var resp = registerEndpoint("ftp://bad.example.com/file", false);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("file:// URL is rejected with 400")
    void fileUrl_rejected() {
        var resp = registerEndpoint("file:///etc/passwd", false);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("localhost URL is rejected when allow_internal=false")
    void localhostUrl_rejectedByDefault() {
        var resp = registerEndpoint("http://localhost:8080/webhook", false);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("127.0.0.1 URL is rejected when allow_internal=false")
    void loopbackIp_rejectedByDefault() {
        var resp = registerEndpoint("http://127.0.0.1:8080/webhook", false);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("localhost URL allowed when allow_internal=true")
    void localhostUrl_allowedWithFlag() {
        var resp = registerEndpoint("http://localhost:9999/webhook", true);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("Missing URL returns 400 Bad Request")
    void missingUrl_returns400() {
        RegisterEndpointRequest req = new RegisterEndpointRequest();
        // URL intentionally null
        req.setEventTypes(List.of("payment.success"));

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", "makemytrip");
        headers.setContentType(MediaType.APPLICATION_JSON);

        var resp = restTemplate.exchange("/api/v1/endpoints", HttpMethod.POST,
                new HttpEntity<>(req, headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Empty event types list returns 400 Bad Request")
    void emptyEventTypes_returns400() {
        RegisterEndpointRequest req = new RegisterEndpointRequest();
        req.setUrl("https://example.com/webhook");
        req.setEventTypes(List.of()); // Empty list

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", "makemytrip");
        headers.setContentType(MediaType.APPLICATION_JSON);

        var resp = restTemplate.exchange("/api/v1/endpoints", HttpMethod.POST,
                new HttpEntity<>(req, headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<String> registerEndpoint(String url, boolean allowInternal) {
        RegisterEndpointRequest req = new RegisterEndpointRequest();
        req.setUrl(url);
        req.setEventTypes(List.of("payment.success"));
        req.setAllowInternal(allowInternal);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", "makemytrip");
        headers.setContentType(MediaType.APPLICATION_JSON);

        return restTemplate.exchange("/api/v1/endpoints", HttpMethod.POST,
                new HttpEntity<>(req, headers), String.class);
    }
}
