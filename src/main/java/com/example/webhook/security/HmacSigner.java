package com.example.webhook.security;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * HMAC-SHA256 webhook request signer.
 *
 * Signature = Base64( HMAC-SHA256( body + "." + timestamp, secret ) )
 *
 * The timestamp is included in the signed payload to prevent replay attacks.
 * Receivers can verify the signature by computing the same HMAC using the shared secret.
 */
@Component
public class HmacSigner {

    private static final String ALGORITHM = "HmacSHA256";

    /**
     * Compute HMAC-SHA256 signature over "body.timestamp" with the endpoint secret.
     * Returns the Base64-encoded signature.
     *
     * @param body      raw request body bytes (what will be sent as the HTTP body)
     * @param secret    per-endpoint signing secret (never logged)
     * @param timestamp Unix epoch milliseconds as a string
     * @return Base64-encoded HMAC-SHA256 signature
     */
    public String sign(byte[] body, String secret, String timestamp) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
            mac.init(keySpec);
            // Sign: body + "." + timestamp
            mac.update(body);
            mac.update((byte) '.');
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            byte[] rawHmac = mac.doFinal();
            return Base64.getEncoder().encodeToString(rawHmac);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256 signature", e);
        }
    }

    /**
     * Verify a signature.
     */
    public boolean verify(byte[] body, String secret, String timestamp, String expectedSignature) {
        String computed = sign(body, secret, timestamp);
        return computed.equals(expectedSignature);
    }
}
