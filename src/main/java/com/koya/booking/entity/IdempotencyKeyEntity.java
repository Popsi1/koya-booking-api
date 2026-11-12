package com.koya.booking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A stored response for an Idempotency-Key, replayed byte-for-byte on a matching retry. */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKeyEntity {

    @Id
    private String key;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Column(name = "status_code", nullable = false)
    private int statusCode;

    @Column(nullable = false)
    private String body;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IdempotencyKeyEntity() {}

    public IdempotencyKeyEntity(String key, String requestHash, int statusCode, String body, Instant createdAt) {
        this.key = key;
        this.requestHash = requestHash;
        this.statusCode = statusCode;
        this.body = body;
        this.createdAt = createdAt;
    }

    public String getKey() { return key; }
    public String getRequestHash() { return requestHash; }
    public int getStatusCode() { return statusCode; }
    public String getBody() { return body; }
}
