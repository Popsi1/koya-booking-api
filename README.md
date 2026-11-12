# Clinic Booking API (Java)

A Java 21 and Spring Boot 3 version of the clinic booking API, using Spring Data JPA and Postgres. It has the same contract and behaviour as the Go version (`../koya-booking-api`) and implements the Koya assessment spec (`koyaacademy.com/assessment-api`). A web page is served at `/`.

## Endpoints

Every endpoint below needs the `X-API-Key` header.

| Method | Path | Level |
|---|---|---|
| POST | `/reset` | 1: replace all slots and delete all bookings |
| GET | `/slots?provider=` | 1: list slots, earliest first, with `available` |
| POST | `/bookings` | 1–5: book a slot |
| GET | `/bookings/{id}` | 1: get a booking |
| DELETE | `/bookings/{id}` | 3: cancel a booking |
| GET | `/bookings?email=&limit=&cursor=` | 4: list one person's bookings, oldest first, with cursor pagination |

Errors are always returned as `{"error": "..."}`, including unknown routes (404) and wrong methods (405). The health check at `GET /healthz` doesn't need a key.

## How each level is handled

- **Validation (level 2).** `name` and `email` are trimmed and `email` is stored in lower case. Requests are rejected with:
  - 400 when a field is missing or empty, the email is invalid, or the slot has already started.
  - 404 when the slot doesn't exist.
- **No double booking (level 3).** A booking first locks the slot row with a JPA pessimistic lock (`SELECT ... FOR UPDATE`). It then checks for a confirmed booking and inserts. Requests for the same slot therefore run one at a time, so only one succeeds and the rest get 409. A partial unique index on `bookings(slot_id) WHERE status = 'confirmed'` backs this up at the database level. Cancelling a booking frees the slot.
- **Idempotency (level 4).** The request takes a Postgres advisory lock on the `Idempotency-Key`, so concurrent retries run one after another.
  - The stored response (status and body) is replayed when the request body has the same hash. A different body returns 422.
  - Everything except 429 and 5xx is stored.
- **Pagination (level 4).** The opaque cursor is the booking's sequence number. The API fetches `limit + 1` rows; if the extra row is missing, `next_cursor` is `null`.
- **Rate limit (level 5).** Each email gets 5 attempts in a sliding 60-second window, and failed attempts count. The attempts are kept in Postgres under a per-email advisory lock. Going over returns 429 with `Retry-After` in whole seconds.

All the database work for a booking request happens in one transaction.

## Code map

| File | Role |
|---|---|
| `ApiController` | Routes, validation and the booking pipeline. |
| `Store` | All data access, through JPA. |
| `entity/` | JPA entities. |
| `repository/` | Spring Data repositories. |
| `ApiKeyFilter` | The `X-API-Key` check. |
| `ErrorHandling` | Turns every error into JSON. |
| `DatabaseConfig` | Converts Render's `postgres://` URL to JDBC. |
| `schema.sql` | The schema. Spring SQL init runs it on every start. Hibernate's `ddl-auto` is off because JPA can't express the partial unique index. |

## Web page

The page lists slots (with a provider filter), books a slot, shows the confirmation, cancels it, and pages through a person's bookings. It calls the same handlers mounted at `/web/api/*`. The API key stays on the server, and validation, conflict detection and rate limiting still apply. On an empty database the server seeds demo slots for the next 3 days.

## Run locally

The easiest way needs only Docker. It starts Postgres and the app together:

```sh
docker compose up --build        # open http://localhost:8081  (API key: dev)
```

Alternatively, run it with JDK 21 against an existing Postgres:

```sh
export DATABASE_URL="postgres://postgres:pg@localhost:5433/koya_java?sslmode=disable" API_KEY=dev
./mvnw spring-boot:run           # http://localhost:8080
```

## Tests

These are integration tests against a real Postgres, and they **wipe the database** they point at:

```sh
TEST_DATABASE_URL="postgres://postgres:pg@localhost:5433/koya_java_test?sslmode=disable" ./mvnw test
```

## Deploy (Render)

`render.yaml` is a Render Blueprint. It creates a free Postgres database and a Docker web service, wires `DATABASE_URL` from the database, and generates `API_KEY`.

1. In the Render dashboard, open **New → Blueprint** and select this repo.
2. Click **Apply**.
3. Find the key under the service's **Environment** tab. You can replace it with your own value.
# koya-booking-api
