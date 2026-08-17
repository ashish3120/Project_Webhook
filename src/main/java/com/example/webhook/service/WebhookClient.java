package com.example.webhook.service;

import com.example.webhook.dto.TestEndpointResponse;
import com.example.webhook.entity.Endpoint;
import com.example.webhook.security.HmacSigner;
import tools.jackson.databind.json.JsonMapper;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;
import reactor.netty.tcp.TcpClient;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Performs outbound webhook HTTP deliveries using Spring WebClient.
 *
 * Design:
 * - Explicit connect timeout (fail fast if TCP can't connect)
 * - Explicit read timeout (fail fast if server hangs after connecting)
 * - HMAC-SHA256 signature on every call
 * - Returns raw result (response code, latency, snippet) — does not update DB
 * - DB state updates happen in DeliveryWorker after this call completes
 *
 * CRITICAL: This class NEVER holds a database transaction.
 * All DB writes happen before or after this method, never during.
 */
@Component
@Slf4j
public class WebhookClient {

    private final WebClient webClient;
    private final HmacSigner hmacSigner;
    private final JsonMapper objectMapper;

    private static final int SNIPPET_MAX_LENGTH = 500;

    public WebhookClient(
            HmacSigner hmacSigner,
            JsonMapper objectMapper,
            @Value("${webhook.client.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${webhook.client.read-timeout-ms:10000}") int readTimeoutMs) {

        this.hmacSigner = hmacSigner;
        this.objectMapper = objectMapper;

        // Build reactor-netty HttpClient with explicit timeouts
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .doOnConnected(conn ->
                    conn.addHandlerLast(new ReadTimeoutHandler(readTimeoutMs, TimeUnit.MILLISECONDS)));

        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    /**
     * Deliver webhook payload to the target endpoint.
     *
     * @return DeliveryResult with HTTP status code, latency, and response snippet
     */
    public DeliveryResult deliver(String targetUrl, String secret, Object payload) {
        long startMs = System.currentTimeMillis();
        String timestamp = String.valueOf(System.currentTimeMillis());

        try {
            byte[] bodyBytes = objectMapper.writeValueAsBytes(payload);
            String signature = hmacSigner.sign(bodyBytes, secret, timestamp);

            String responseBody = webClient.post()
                    .uri(targetUrl)
                    .header("Content-Type", "application/json")
                    .header("X-Webhook-Signature", signature)
                    .header("X-Webhook-Timestamp", timestamp)
                    .bodyValue(bodyBytes)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(60)); // outer safety net

            long latencyMs = System.currentTimeMillis() - startMs;
            String snippet = truncate(responseBody);

            log.debug("Webhook delivered: url={} latencyMs={}", targetUrl, latencyMs);
            return DeliveryResult.success(200, latencyMs, snippet);

        } catch (WebClientResponseException ex) {
            long latencyMs = System.currentTimeMillis() - startMs;
            String snippet = truncate(ex.getResponseBodyAsString());
            log.warn("Webhook failed with HTTP {}: url={} latencyMs={}", ex.getStatusCode().value(), targetUrl, latencyMs);
            return DeliveryResult.failure(ex.getStatusCode().value(), latencyMs, snippet, null);

        } catch (Exception ex) {
            long latencyMs = System.currentTimeMillis() - startMs;
            String errorMsg = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            // Truncate the error message — do not log raw exception to avoid sensitive data leakage
            log.warn("Webhook delivery error: url={} error={} latencyMs={}", targetUrl,
                    ex.getClass().getSimpleName(), latencyMs);
            return DeliveryResult.error(latencyMs, errorMsg);
        }
    }

    /**
     * Send a synthetic test ping to verify endpoint reachability.
     */
    public TestEndpointResponse sendTestPing(Endpoint endpoint) {
        Map<String, Object> testPayload = Map.of(
                "type", "webhook.test",
                "message", "Webhook endpoint test ping",
                "timestamp", System.currentTimeMillis()
        );

        DeliveryResult result = deliver(endpoint.getUrl(), endpoint.getSecret(), testPayload);
        return TestEndpointResponse.builder()
                .reachable(result.getResponseCode() != null || result.getLatencyMs() > 0)
                .success(result.isSuccess())
                .responseCode(result.getResponseCode())
                .responseSnippet(result.getResponseSnippet())
                .latencyMs(result.getLatencyMs())
                .error(result.getError())
                .build();
    }

    private String truncate(String s) {
        if (s == null) return null;
        return s.length() > SNIPPET_MAX_LENGTH ? s.substring(0, SNIPPET_MAX_LENGTH) + "..." : s;
    }

    /**
     * Immutable result of a single webhook delivery attempt.
     */
    public static class DeliveryResult {
        private final Integer responseCode;
        private final long latencyMs;
        private final String responseSnippet;
        private final String error;
        private final boolean success;

        private DeliveryResult(Integer responseCode, long latencyMs, String responseSnippet, String error, boolean success) {
            this.responseCode = responseCode;
            this.latencyMs = latencyMs;
            this.responseSnippet = responseSnippet;
            this.error = error;
            this.success = success;
        }

        public static DeliveryResult success(int responseCode, long latencyMs, String snippet) {
            return new DeliveryResult(responseCode, latencyMs, snippet, null, true);
        }

        public static DeliveryResult failure(int responseCode, long latencyMs, String snippet, String error) {
            return new DeliveryResult(responseCode, latencyMs, snippet, error, false);
        }

        public static DeliveryResult error(long latencyMs, String error) {
            return new DeliveryResult(null, latencyMs, null, error, false);
        }

        public Integer getResponseCode() { return responseCode; }
        public long getLatencyMs() { return latencyMs; }
        public String getResponseSnippet() { return responseSnippet; }
        public String getError() { return error; }
        public boolean isSuccess() { return success; }
    }
}
