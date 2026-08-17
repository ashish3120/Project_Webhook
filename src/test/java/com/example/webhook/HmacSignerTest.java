package com.example.webhook;

import com.example.webhook.security.HmacSigner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for HMAC-SHA256 signing.
 *
 * Verifies:
 * 1. Same input + secret + timestamp = same output (deterministic)
 * 2. Different secrets produce different signatures
 * 3. Different payloads produce different signatures
 * 4. Signature is Base64 encoded
 * 5. Verification works correctly
 */
@DisplayName("HMAC Signer Unit Tests")
class HmacSignerTest {

    private final HmacSigner signer = new HmacSigner();

    @Test
    @DisplayName("Sign is deterministic for same inputs")
    void sign_isDeterministic() {
        byte[] body = "test payload".getBytes(StandardCharsets.UTF_8);
        String secret = "my-secret-key";
        String timestamp = "1700000000000";

        String sig1 = signer.sign(body, secret, timestamp);
        String sig2 = signer.sign(body, secret, timestamp);

        assertThat(sig1).isEqualTo(sig2);
    }

    @Test
    @DisplayName("Different secrets produce different signatures")
    void differentSecrets_differentSignatures() {
        byte[] body = "payload".getBytes(StandardCharsets.UTF_8);
        String timestamp = "1700000000000";

        String sig1 = signer.sign(body, "secret-A", timestamp);
        String sig2 = signer.sign(body, "secret-B", timestamp);

        assertThat(sig1).isNotEqualTo(sig2);
    }

    @Test
    @DisplayName("Different payloads produce different signatures")
    void differentPayloads_differentSignatures() {
        String secret = "shared-secret";
        String timestamp = "1700000000000";

        String sig1 = signer.sign("payload-1".getBytes(StandardCharsets.UTF_8), secret, timestamp);
        String sig2 = signer.sign("payload-2".getBytes(StandardCharsets.UTF_8), secret, timestamp);

        assertThat(sig1).isNotEqualTo(sig2);
    }

    @Test
    @DisplayName("Signature is valid Base64")
    void signature_isBase64() {
        byte[] body = "test".getBytes(StandardCharsets.UTF_8);
        String signature = signer.sign(body, "secret", "timestamp");

        // Should not throw
        byte[] decoded = Base64.getDecoder().decode(signature);
        assertThat(decoded).hasSize(32); // HMAC-SHA256 = 32 bytes
    }

    @Test
    @DisplayName("verify returns true for correct signature")
    void verify_returnsTrueForCorrectSignature() {
        byte[] body = "webhook body".getBytes(StandardCharsets.UTF_8);
        String secret = "endpoint-secret";
        String timestamp = "1700000000000";

        String signature = signer.sign(body, secret, timestamp);
        assertThat(signer.verify(body, secret, timestamp, signature)).isTrue();
    }

    @Test
    @DisplayName("verify returns false for tampered payload")
    void verify_returnsFalseForTamperedPayload() {
        byte[] original = "original payload".getBytes(StandardCharsets.UTF_8);
        byte[] tampered = "tampered payload".getBytes(StandardCharsets.UTF_8);
        String secret = "endpoint-secret";
        String timestamp = "1700000000000";

        String signature = signer.sign(original, secret, timestamp);
        assertThat(signer.verify(tampered, secret, timestamp, signature)).isFalse();
    }

    @Test
    @DisplayName("verify returns false for wrong secret")
    void verify_returnsFalseForWrongSecret() {
        byte[] body = "payload".getBytes(StandardCharsets.UTF_8);
        String timestamp = "1700000000000";

        String signature = signer.sign(body, "correct-secret", timestamp);
        assertThat(signer.verify(body, "wrong-secret", timestamp, signature)).isFalse();
    }
}
