package com.example.webhook.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates cryptographically secure per-endpoint signing secrets.
 * Uses SecureRandom (CSPRNG) — never Math.random() or UUID.
 */
@Component
public class SecretGenerator {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int SECRET_BYTE_LENGTH = 32; // 256 bits = 32 bytes

    /**
     * Generate a 256-bit Base64URL-encoded secret.
     */
    public String generate() {
        byte[] bytes = new byte[SECRET_BYTE_LENGTH];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
