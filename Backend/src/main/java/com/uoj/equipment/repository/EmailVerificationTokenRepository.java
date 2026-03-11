package com.uoj.equipment.repository;

import com.uoj.equipment.entity.EmailVerificationToken;
import com.uoj.equipment.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, Long> {
    Optional<EmailVerificationToken> findByToken(String token);
    void deleteByUser(User user);
}