-- V1__init_webhook_schema.sql
-- Reliable Webhook Delivery Service — Initial Schema

-- ============================================================
-- TENANTS
-- ============================================================
CREATE TABLE tenants (
    id          UUID        NOT NULL DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_tenants PRIMARY KEY (id),
    CONSTRAINT uq_tenants_name UNIQUE (name)
);

-- ============================================================
-- ENDPOINTS
-- ============================================================
CREATE TABLE endpoints (
    id                      UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id               UUID        NOT NULL,
    url                     TEXT        NOT NULL,
    secret                  TEXT        NOT NULL,
    subscribed_event_types  TEXT[]      NOT NULL DEFAULT '{}',
    status                  VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    allow_internal          BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_endpoints PRIMARY KEY (id),
    CONSTRAINT fk_endpoints_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT chk_endpoints_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE INDEX idx_endpoints_tenant_id ON endpoints (tenant_id);
CREATE INDEX idx_endpoints_status ON endpoints (tenant_id, status);

-- ============================================================
-- EVENTS
-- ============================================================
CREATE TABLE events (
    id                  UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id           UUID        NOT NULL,
    event_id_external   VARCHAR(255) NOT NULL,
    type                VARCHAR(255) NOT NULL,
    payload             JSONB       NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_events PRIMARY KEY (id),
    CONSTRAINT fk_events_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT uq_events_tenant_external UNIQUE (tenant_id, event_id_external)
);

CREATE INDEX idx_events_tenant_id ON events (tenant_id);
CREATE INDEX idx_events_type ON events (tenant_id, type);

-- ============================================================
-- DELIVERIES
-- ============================================================
CREATE TABLE deliveries (
    id                      UUID        NOT NULL DEFAULT gen_random_uuid(),
    event_id                UUID        NOT NULL,
    endpoint_id             UUID        NOT NULL,
    tenant_id               UUID        NOT NULL,
    status                  VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count           INT         NOT NULL DEFAULT 0,
    next_attempt_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    locked_by               VARCHAR(255),
    locked_until            TIMESTAMPTZ,
    last_response_code      INT,
    last_response_snippet   TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_deliveries PRIMARY KEY (id),
    CONSTRAINT fk_deliveries_event    FOREIGN KEY (event_id)    REFERENCES events(id)    ON DELETE CASCADE,
    CONSTRAINT fk_deliveries_endpoint FOREIGN KEY (endpoint_id) REFERENCES endpoints(id) ON DELETE CASCADE,
    CONSTRAINT fk_deliveries_tenant   FOREIGN KEY (tenant_id)   REFERENCES tenants(id)   ON DELETE CASCADE,
    CONSTRAINT chk_deliveries_status  CHECK (status IN ('PENDING', 'IN_PROGRESS', 'DELIVERED', 'FAILED', 'DEAD_LETTERED'))
);

-- Composite index for the due-delivery claim query (no NOW() in predicate - PostgreSQL restriction)
-- The query: WHERE status='PENDING' AND next_attempt_at <= NOW() AND (locked_until IS NULL OR locked_until <= NOW())
CREATE INDEX idx_deliveries_due_claim ON deliveries (status, next_attempt_at, locked_until);

CREATE INDEX idx_deliveries_tenant_id   ON deliveries (tenant_id);
CREATE INDEX idx_deliveries_endpoint_id ON deliveries (endpoint_id);
CREATE INDEX idx_deliveries_event_id    ON deliveries (event_id);

-- ============================================================
-- DELIVERY ATTEMPTS (immutable ledger)
-- ============================================================
CREATE TABLE delivery_attempts (
    id               UUID        NOT NULL DEFAULT gen_random_uuid(),
    delivery_id      UUID        NOT NULL,
    attempt_number   INT         NOT NULL,
    response_code    INT,
    latency_ms       BIGINT,
    error            TEXT,
    response_snippet TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_delivery_attempts   PRIMARY KEY (id),
    CONSTRAINT fk_delivery_attempts_delivery FOREIGN KEY (delivery_id) REFERENCES deliveries(id) ON DELETE CASCADE
);

CREATE INDEX idx_delivery_attempts_delivery_id ON delivery_attempts (delivery_id);

-- ============================================================
-- Seed data: Bootstrap well-known tenants for development
-- ============================================================
INSERT INTO tenants (id, name) VALUES
    ('00000000-0000-0000-0000-000000000001', 'makemytrip'),
    ('00000000-0000-0000-0000-000000000002', 'googlepay')
ON CONFLICT (name) DO NOTHING;
