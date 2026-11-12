package com.koya.booking.repository;

import com.koya.booking.entity.BookingEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<BookingEntity, String> {

    boolean existsBySlotIdAndStatus(String slotId, String status);

    @Query("select b.slotId from BookingEntity b where b.status = 'confirmed'")
    List<String> findConfirmedSlotIds();

    List<BookingEntity> findByEmailAndSeqGreaterThanOrderBySeqAsc(String email, long seq, Limit limit);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from BookingEntity b where b.id = :id")
    Optional<BookingEntity> findByIdForUpdate(@Param("id") String id);
}
