package com.uoj.equipment.service;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.uoj.equipment.entity.PasswordResetToken;
import com.uoj.equipment.entity.User;
import com.uoj.equipment.repository.PasswordResetTokenRepository;
import com.uoj.equipment.repository.UserRepository;

@Service
public class PasswordResetService {

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;

    // Read from application.properties — set to your deployed frontend URL
    @Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    public PasswordResetService(UserRepository userRepository,
                                PasswordResetTokenRepository tokenRepository,
                                PasswordEncoder passwordEncoder,
                                EmailService emailService) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
    }

    // Step 1: user submits email → generate reset token and send email.
    @Transactional
    public void createResetTokenForEmail(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("No user with this email"));

        String token = UUID.randomUUID().toString();
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(30);

        PasswordResetToken prt = new PasswordResetToken(token, user, expiresAt);
        tokenRepository.save(prt);

        // Uses app.frontend.base-url from application.properties (NOT hardcoded localhost)
        String resetLink = frontendBaseUrl + "/reset-password?token=" + token;

        String subject = "Password Reset Request - Equipment Request System";
        String body = "Dear " + user.getFullName() + ",\n\n"
                + "We received a request to reset your password.\n"
                + "Please click the link below (or paste into your browser) to set a new password:\n\n"
                + resetLink + "\n\n"
                + "This link will expire in 30 minutes.\n\n"
                + "If you did not request this, you can safely ignore this email.\n\n"
                + "Thank you,\n"
                + "Equipment Request System\n"
                + "Faculty of Engineering, University of Jaffna";

        emailService.sendPlainTextEmail(user.getEmail(), subject, body);
    }

    // Step 2: user sends token + new password → validate token and update password.
    @Transactional
    public void resetPassword(String token, String newPassword) {
        PasswordResetToken prt = tokenRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or unknown token"));

        if (prt.isUsed()) {
            throw new IllegalArgumentException("Token already used");
        }
        if (prt.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("Token expired");
        }

        User user = prt.getUser();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        prt.setUsed(true);
        tokenRepository.save(prt);
    }
}