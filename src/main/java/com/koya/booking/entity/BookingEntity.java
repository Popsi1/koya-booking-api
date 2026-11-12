package com.koya.booking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Generated;

@Entity
@Table(name = "bookings")
public class BookingEntity {

    /** Insertion order from the BIGSERIAL column; used for "oldest first" and as the pagination cursor. */
    @Generated
    @Column(insertable = false, updatable = false)
    private Long seq;

    @Id
    private String id;

    @Column(name = "slot_id", nullable = false)
    private String slotId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BookingEntity() {}

    public BookingEntity(String id, String slotId, String name, String email, String status, Instant createdAt) {
        this.id = id;
        this.slotId = slotId;
        this.name = name;
        this.email = email;
        this.status = status;
        this.createdAt = createdAt;
    }

    public Long getSeq() { return seq; }
    public String getId() { return id; }
    public String getSlotId() { return slotId; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }

    public void setStatus(String status) { this.status = status; }
}
