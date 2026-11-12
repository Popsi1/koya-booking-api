package com.koya.booking;

import com.koya.booking.entity.BookingAttemptEntity;
import com.koya.booking.entity.BookingEntity;
import com.koya.booking.entity.IdempotencyKeyEntity;
import com.koya.booking.entity.SlotEntity;
import com.koya.booking.repository.BookingAttemptRepository;
import com.koya.booking.repository.BookingRepository;
import com.koya.booking.repository.IdempotencyKeyRepository;
import com.koya.booking.repository.SlotRepository;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * All database access, through JPA. Methods without their own transaction join the caller's
 * (the controller wraps each booking request in one TransactionTemplate transaction).
 */
@Service
public class Store {

    public record Slot(String id, String provider, Instant start, Instant end) {}

    public record SlotAvailability(Slot slot, boolean available) {}

    public record Booking(long seq, String id, String slotId, String name, String email,
                          String status, Instant createdAt) {}

    public record IdempotencyRecord(String requestHash, int statusCode, String body) {}

    public record RateResult(boolean allowed, Duration retryAfter) {}

    public static final class NotFoundException extends RuntimeException {}

    public static final class AlreadyCancelledException extends RuntimeException {}

    private static final String CONFIRMED = "confirmed";
    private static final String CANCELLED = "cancelled";

    private final EntityManager em;
    private final SlotRepository slots;
    private final BookingRepository bookings;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final BookingAttemptRepository attempts;
    private final TransactionTemplate tx;

    public Store(EntityManager em, SlotRepository slots, BookingRepository bookings,
                 IdempotencyKeyRepository idempotencyKeys, BookingAttemptRepository attempts, TransactionTemplate tx) {
        this.em = em;
        this.slots = slots;
        this.bookings = bookings;
        this.idempotencyKeys = idempotencyKeys;
        this.attempts = attempts;
        this.tx = tx;
    }

    private static Slot toSlot(SlotEntity s) {
        return new Slot(s.getId(), s.getProvider(), s.getStartAt(), s.getEndAt());
    }

    private static Booking toBooking(BookingEntity b) {
        return new Booking(b.getSeq(), b.getId(), b.getSlotId(), b.getName(), b.getEmail(), b.getStatus(), b.getCreatedAt());
    }

    public void seedIfEmpty(List<Slot> demo) {
        tx.executeWithoutResult(status -> {
            if (slots.count() > 0) {
                return;
            }
            demo.forEach(s -> em.persist(new SlotEntity(s.id(), s.provider(), s.start(), s.end())));
        });
    }

    /** Deletes all bookings, idempotency records and rate-limit attempts, then replaces every slot, atomically. */
    public void reset(List<Slot> newSlots) {
        tx.executeWithoutResult(status -> {
            em.createNativeQuery("TRUNCATE bookings, slots, idempotency_keys, booking_attempts RESTART IDENTITY")
                    .executeUpdate();
            em.clear();
            newSlots.forEach(s -> em.persist(new SlotEntity(s.id(), s.provider(), s.start(), s.end())));
            em.flush();
        });
    }

    public List<SlotAvailability> listSlots(String provider) {
        List<SlotEntity> list = provider == null || provider.isEmpty()
                ? slots.findAllByOrderByStartAtAscIdAsc()
                : slots.findByProviderOrderByStartAtAscIdAsc(provider);
        Set<String> booked = new HashSet<>(bookings.findConfirmedSlotIds());
        return list.stream().map(s -> new SlotAvailability(toSlot(s), !booked.contains(s.getId()))).toList();
    }

    /**
     * Loads the slot with a row lock (SELECT ... FOR UPDATE) held until the transaction ends. Every booking
     * attempt for a slot goes through this lock, so attempts on the same slot run one at a time.
     */
    public Optional<Slot> lockSlot(String id) {
        return slots.findByIdForUpdate(id).map(Store::toSlot);
    }

    /**
     * Creates a confirmed booking, or returns empty if the slot already has one. Call after lockSlot in the
     * same transaction: the row lock is what makes the check-then-insert safe under concurrency. The partial
     * unique index on bookings(slot_id) WHERE status = 'confirmed' is a second, database-level guarantee.
     */
    public Optional<Booking> insertBooking(String slotId, String name, String email) {
        if (bookings.existsBySlotIdAndStatus(slotId, CONFIRMED)) {
            return Optional.empty();
        }
        BookingEntity b = new BookingEntity(UUID.randomUUID().toString(), slotId, name, email, CONFIRMED,
                Instant.now().truncatedTo(ChronoUnit.MILLIS));
        em.persist(b);
        em.flush(); // runs the INSERT now and reads back the generated seq
        return Optional.of(toBooking(b));
    }

    public Optional<Booking> getBooking(String id) {
        return bookings.findById(id).map(Store::toBooking);
    }

    public Booking cancelBooking(String id) {
        return tx.execute(status -> {
            BookingEntity b = bookings.findByIdForUpdate(id).orElseThrow(NotFoundException::new);
            if (CANCELLED.equals(b.getStatus())) {
                throw new AlreadyCancelledException();
            }
            b.setStatus(CANCELLED);
            em.flush();
            return toBooking(b);
        });
    }

    /** Up to limit+1 bookings after the given seq, oldest first; the extra row signals another page. */
    public List<Booking> listBookingsByEmail(String email, long afterSeq, int limit) {
        return bookings.findByEmailAndSeqGreaterThanOrderBySeqAsc(email, afterSeq, Limit.of(limit + 1))
                .stream().map(Store::toBooking).toList();
    }

    /**
     * Records a booking attempt if the email is under the limit for the window. Must run inside a transaction:
     * the per-email advisory lock is held until it ends, so simultaneous attempts are counted one at a time.
     */
    public RateResult registerAttempt(String email, int limit, Duration window) {
        attempts.advisoryXactLock("rl:" + email);
        Instant now = Instant.now();
        attempts.deleteOlderThan(email, now.minus(window));
        if (attempts.countByEmail(email) >= limit) {
            Instant oldest = attempts.findOldestAt(email);
            return new RateResult(false, oldest == null ? Duration.ZERO : Duration.between(now, oldest.plus(window)));
        }
        em.persist(new BookingAttemptEntity(email, now));
        return new RateResult(true, Duration.ZERO);
    }

    /** Serialises requests sharing a key until the transaction ends, then returns the stored response if any. */
    public Optional<IdempotencyRecord> lockIdempotencyKey(String key) {
        attempts.advisoryXactLock("idem:" + key);
        return idempotencyKeys.findById(key)
                .map(k -> new IdempotencyRecord(k.getRequestHash(), k.getStatusCode(), k.getBody()));
    }

    public void saveIdempotencyKey(String key, IdempotencyRecord rec) {
        em.persist(new IdempotencyKeyEntity(key, rec.requestHash(), rec.statusCode(), rec.body(), Instant.now()));
    }

    public void ping() {
        em.createNativeQuery("SELECT 1").getSingleResult();
    }
}
