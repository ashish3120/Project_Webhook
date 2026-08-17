package com.example.webhook.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Immutable ledger of every outbound HTTP attempt.
 * Once created, rows in this table are NEVER updated.
 */
@Entity
@Table(name = "delivery_attempts")
@Getter
@Setter
@NoArgsConstructor
public class DeliveryAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "delivery_id", nullable = false)
    private UUID deliveryId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "response_code")
    private Integer responseCode;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "response_snippet", columnDefinition = "TEXT")
    private String responseSnippet;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
