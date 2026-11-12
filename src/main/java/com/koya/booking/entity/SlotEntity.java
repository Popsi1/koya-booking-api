package com.koya.booking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "slots")
public class SlotEntity {

    @Id
    private String id;

    @Column(nullable = false)
    private String provider;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    protected SlotEntity() {}

    public SlotEntity(String id, String provider, Instant startAt, Instant endAt) {
        this.id = id;
        this.provider = provider;
        this.startAt = startAt;
        this.endAt = endAt;
    }

    public String getId() { return id; }
    public String getProvider() { return provider; }
    public Instant getStartAt() { return startAt; }
    public Instant getEndAt() { return endAt; }
}
