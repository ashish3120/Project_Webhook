# Reliable Webhook Delivery Service

A production-ready, multi-tenant, asynchronous webhook delivery platform. It guarantees **at-least-once delivery**, enforces strict **tenant isolation**, and ensures **high availability** and concurrency utilizing database-level locking.

## 1. High-Level Architecture Overview

### Tech Stack
*   **Language**: Java 21
*   **Framework**: Spring Boot 4.1.0
*   **Database**: PostgreSQL 16
*   **Migrations**: Flyway
*   **Resilience**: Resilience4j (Circuit Breakers)
*   **API Documentation**: Swagger UI / OpenAPI 3
*   **Testing**: Testcontainers & WireMock

---

## 2. Running the Project Locally

The project is fully containerized for a one-click local setup.

### Prerequisites
*   Docker & Docker Compose
*   Java 21 (if you wish to run the test suite locally)
*   Maven (if building from source)

### Start the Application
To start the application and the PostgreSQL database, run:
```bash
docker-compose up -d --build
```

The application will start on **port 8080**.

### Stop the Application
```bash
docker-compose down
```

---

## 3. Demo Tenants

For local evaluation, the database is initialized with two demo tenants through Flyway migrations:

| Tenant | `X-Tenant-Id` |
|---|---|
| MakeMyTrip | `makemytrip` |
| GooglePay | `googlepay` |

Use one of these tenant IDs in every API request.

Example:

```text
X-Tenant-Id: makemytrip
```

These tenants are provided only as demo/test tenants. A fresh database is initialized with them automatically when the Flyway migrations run.

---

## 4. Interactive API Documentation (Swagger UI)

You can explore, read the full API documentation, and send requests interactively via the built-in Swagger UI:

👉 **[http://localhost:8080/swagger-ui/index.html](http://localhost:8080/swagger-ui/index.html)**

---

## 5. Using the API (via cURL)

If you prefer using the terminal, here are the primary interactions. Note: Every request must include the `X-Tenant-Id` header for tenant isolation. 

**(Note for Windows PowerShell users: use `curl.exe` instead of `curl` to bypass the built-in alias).**

### Check Health
```bash
curl.exe http://localhost:8080/actuator/health
```

### Register an Endpoint
```bash
curl.exe -X POST http://localhost:8080/api/v1/endpoints \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: makemytrip" \
  -d '{
    "url": "https://httpbin.org/post",
    "eventTypes": ["payment.succeeded"],
    "description": "My Webhook"
  }'
```

### Send an Event
Trigger an event. The system will immediately return a `202 Accepted` and will asynchronously deliver it to the endpoint registered above.
```bash
curl.exe -X POST http://localhost:8080/api/v1/events \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: makemytrip" \
  -d '{
    "eventId": "evt_987",
    "eventType": "payment.succeeded",
    "payload": {
      "amount": 500,
      "currency": "USD"
    }
  }'
```

## Quick Evaluation Flow

### 1. Start the service

```bash
docker compose up -d --build
```

Open Swagger:

http://localhost:8080/swagger-ui/index.html

### 2. Register a webhook endpoint

Use:

POST /api/v1/endpoints
X-Tenant-Id: makemytrip

Request body:

```json
{
  "url": "https://httpbin.org/post",
  "eventTypes": ["payment.succeeded"],
  "description": "Demo webhook"
}
```

Expected:

`201 Created`

Save the returned `endpointId`.

### 3. Send an event

Use:

POST /api/v1/events
X-Tenant-Id: makemytrip

Request body:

```json
{
  "eventId": "demo-001",
  "type": "payment.succeeded",
  "payload": {
    "amount": "500",
    "currency": "USD"
  }
}
```

Expected:

`202 Accepted`

Save the returned `eventId` UUID.

### 4. Check the delivery

Use:

GET /api/v1/events/{eventId}/deliveries
X-Tenant-Id: makemytrip

Replace `{eventId}` with the UUID returned by the previous request.

Expected:

```text
status: DELIVERED
attemptCount: 1
lastResponseCode: 200
```

### 5. Test idempotency

Send the exact same event again using:

`eventId: demo-001`

Expected:

```json
{
  "status": "ALREADY_ACCEPTED",
  "deliveriesCreated": 0
}
```

### 6. Test tenant isolation

Using the `endpointId` created under `makemytrip`, call:

GET /api/v1/endpoints/{endpointId}
X-Tenant-Id: googlepay

Expected:

`404 Not Found`

The endpoint must not be accessible across tenants.

---

## 6. Core Components & Data Flow

### A. Data Model (PostgreSQL)
The foundation of the service is built on 5 strictly constrained tables:
1.  **`tenants`**: Identifies isolated workspaces (e.g., MakeMyTrip, GooglePay).
2.  **`endpoints`**: Webhook URLs registered by tenants, including the events they subscribe to and their unique HMAC signing secret.
3.  **`events`**: The core event payload ingested from producers. Enforces an idempotency constraint `(tenant_id, event_id_external)`.
4.  **`deliveries`**: A state machine representing the fan-out of an event to an endpoint. Statuses include `PENDING`, `IN_PROGRESS`, `DELIVERED`, and `DEAD_LETTERED`.
5.  **`delivery_attempts`**: An **append-only, immutable ledger** recording the exact outcome (HTTP code, latency, error snippet) of every single HTTP call.

### B. Event Ingestion Pipeline (Synchronous Path)
1.  **Ingestion API (`/api/v1/events`)**: Accepts an event payload.
2.  **Idempotency Check**: The database enforces uniqueness on the external event ID per tenant. Duplicate events are returned as `202 Accepted` but do not create duplicate deliveries.
3.  **Fan-Out**: The system queries all `ACTIVE` endpoints subscribed to the event type.
4.  **Staging**: Creates one `PENDING` delivery record per subscribed endpoint. 
5.  **Fast Response**: The HTTP request immediately returns `202 Accepted`. **No outbound webhook HTTP calls are made during the ingestion HTTP request.**

### C. Delivery Worker Pool (Asynchronous Path)
The heart of the system is the `DeliveryWorker`. It uses a **Two-Transaction Pattern** to guarantee reliability and prevent connection pool exhaustion.

**Transaction 1: Atomic Claiming**
*   The worker executes a query using PostgreSQL's `SELECT ... FOR UPDATE SKIP LOCKED`.
*   This claims a batch of `PENDING` deliveries atomically. If multiple workers poll at the same exact millisecond, `SKIP LOCKED` ensures they seamlessly grab disjoint sets of deliveries without deadlocking.
*   The worker sets `status = IN_PROGRESS` and establishes a lease (`locked_until`). 
*   **Transaction Commits**. The database lock is released.

**HTTP Delivery (No DB Transaction)**
*   The worker executes the HTTP POST request to the client's URL.
*   Because no database transaction is active, a hanging external server will exhaust HTTP threads but *will never lock up the database connection pool*.
*   Before calling, a **Resilience4j Circuit Breaker** checks if the endpoint is healthy. If the endpoint is failing consistently, the call is blocked and immediately postponed.
*   The payload is signed using **HMAC-SHA256**.

**Transaction 2: Recording Results**
*   The HTTP result is recorded in the immutable `delivery_attempts` table.
*   If successful: Delivery is marked `DELIVERED`.
*   If failed: An exponential backoff (with jitter) is calculated, and the delivery is rescheduled (`PENDING` with a future `next_attempt_at`).
*   If max retries are exceeded: Delivery is marked `DEAD_LETTERED`.
*   **Transaction Commits**.

---

## 7. Security & Resilience Models

### Tenant Isolation
*   Every request is scoped by the `X-Tenant-Id` header.
*   **Cross-tenant access is prevented at the application/database query layer by enforcing tenant scoping on resource lookups.** Tenant A cannot view, delete, or redrive Tenant B's deliveries.

### Payload Security (HMAC)
*   When endpoints are created, a cryptographically secure (`SecureRandom`) secret is generated.
*   Outbound requests contain `X-Webhook-Signature` and `X-Webhook-Timestamp`. Subscribers use the secret to verify the payload hasn't been tampered with.

### SSRF Protection
*   The `EndpointUrlValidator` rejects internal/private IP ranges (e.g., `127.0.0.1`, `10.x.x.x`, `localhost`) to prevent the webhook service from attacking internal infrastructure, unless explicitly bypassed for testing.

### Circuit Breakers
*   Each endpoint gets its own isolated Circuit Breaker via **Resilience4j**. If Endpoint A goes down, its circuit opens, preventing the system from bombarding a downed server. Endpoint B's deliveries continue unaffected.

### Fault Tolerance & Crash Recovery
*   If a worker crashes mid-delivery (while `IN_PROGRESS`), its lease (`locked_until`) will eventually expire.
*   Another worker will automatically reclaim the expired delivery and retry it. If a worker crashes after claiming a delivery, the lease eventually expires and another worker can reclaim the delivery.
