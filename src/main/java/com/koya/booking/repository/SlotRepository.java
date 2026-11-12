package com.koya.booking.repository;

import com.koya.booking.entity.SlotEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SlotRepository extends JpaRepository<SlotEntity, String> {

    List<SlotEntity> findAllByOrderByStartAtAscIdAsc();

    List<SlotEntity> findByProviderOrderByStartAtAscIdAsc(String provider);

    /** SELECT ... FOR UPDATE: bookings for the same slot queue up here, one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SlotEntity s where s.id = :id")
    Optional<SlotEntity> findByIdForUpdate(@Param("id") String id);
}
