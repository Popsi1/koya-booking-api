package com.koya.booking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One booking attempt, kept for the per-email rate limit window. */
@Entity
@Table(name = "booking_attempts")
public class BookingAttemptEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private Instant at;

    protected BookingAttemptEntity() {}

    public BookingAttemptEntity(String email, Instant at) {
        this.email = email;
        this.at = at;
    }
}
