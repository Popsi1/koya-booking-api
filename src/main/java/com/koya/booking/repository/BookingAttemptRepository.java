package com.koya.booking.repository;

import com.koya.booking.entity.BookingAttemptEntity;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingAttemptRepository extends JpaRepository<BookingAttemptEntity, Long> {

    @Modifying
    @Query("delete from BookingAttemptEntity a where a.email = :email and a.at <= :cutoff")
    int deleteOlderThan(@Param("email") String email, @Param("cutoff") Instant cutoff);

    long countByEmail(String email);

    @Query("select min(a.at) from BookingAttemptEntity a where a.email = :email")
    Instant findOldestAt(@Param("email") String email);

    /**
     * Postgres transaction-scoped advisory lock, released at commit/rollback. Native because JPA has no
     * equivalent; wrapped in a SELECT 1 so the result maps to an Integer rather than Postgres' void type.
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(:key))) AS l", nativeQuery = true)
    Integer advisoryXactLock(@Param("key") String key);
}
