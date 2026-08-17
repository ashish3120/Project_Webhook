package com.example.webhook.config;

import com.example.webhook.worker.DeliveryWorker;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Custom Actuator health indicator reporting worker pool status.
 * Visible at GET /actuator/health
 *
 * Spring Boot 4.x separated health into its own module: spring-boot-health
 * Package: org.springframework.boot.health.contributor
 */
@Component("workerPool")
public class WorkerPoolHealthIndicator implements HealthIndicator {

    private final DeliveryWorker deliveryWorker;

    public WorkerPoolHealthIndicator(DeliveryWorker deliveryWorker) {
        this.deliveryWorker = deliveryWorker;
    }

    @Override
    public Health health() {
        return Health.up()
                .withDetail("workerId", deliveryWorker.getWorkerId())
                .withDetail("status", "running")
                .build();
    }
}
