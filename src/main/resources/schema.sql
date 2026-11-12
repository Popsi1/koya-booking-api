CREATE TABLE IF NOT EXISTS slots (
    id       TEXT PRIMARY KEY,
    provider TEXT NOT NULL,
    start_at TIMESTAMPTZ NOT NULL,
    end_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS slots_start_idx ON slots (start_at, id);

CREATE TABLE IF NOT EXISTS bookings (
    seq        BIGSERIAL UNIQUE,
    id         TEXT PRIMARY KEY,
    slot_id    TEXT NOT NULL REFERENCES slots (id) ON DELETE CASCADE,
    name       TEXT NOT NULL,
    email      TEXT NOT NULL,
    status     TEXT NOT NULL CHECK (status IN ('confirmed', 'cancelled')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The double-booking guarantee: at most one confirmed booking per slot,
-- enforced by Postgres even under concurrent inserts.
CREATE UNIQUE INDEX IF NOT EXISTS one_confirmed_per_slot
    ON bookings (slot_id) WHERE status = 'confirmed';

CREATE INDEX IF NOT EXISTS bookings_email_seq_idx ON bookings (email, seq);

CREATE TABLE IF NOT EXISTS idempotency_keys (
    key          TEXT PRIMARY KEY,
    request_hash TEXT NOT NULL,
    status_code  INT NOT NULL,
    body         TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS booking_attempts (
    id    BIGSERIAL PRIMARY KEY,
    email TEXT NOT NULL,
    at    TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS booking_attempts_email_at_idx ON booking_attempts (email, at);
