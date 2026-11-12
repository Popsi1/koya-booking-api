package com.koya.booking.repository;

import com.koya.booking.entity.IdempotencyKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyEntity, String> {
}
