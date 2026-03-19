package com.uoj.equipment.repository;

import com.uoj.equipment.entity.PendingSignup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

public interface PendingSignupRepository extends JpaRepository<PendingSignup, Long> {

    Optional<PendingSignup> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByRegNo(String regNo);

    @Modifying
    @Transactional
    @Query("DELETE FROM PendingSignup p WHERE p.email = :email")
    void deleteByEmail(@Param("email") String email);

    @Modifying
    @Transactional
    @Query("DELETE FROM PendingSignup p WHERE p.expiresAt < :now")
    void deleteExpired(@Param("now") LocalDateTime now);
}