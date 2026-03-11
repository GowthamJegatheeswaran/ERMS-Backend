package com.uoj.equipment.service;

import com.uoj.equipment.entity.EmailVerificationToken;
import com.uoj.equipment.entity.User;
import com.uoj.equipment.repository.EmailVerificationTokenRepository;
import com.uoj.equipment.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class EmailVerificationService {

    private final EmailVerificationTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;

    @Value("${app.frontend.base-url:http://localhost:5173}")
    private String frontendBaseUrl;

    public EmailVerificationService(EmailVerificationTokenRepository tokenRepository,
                                    UserRepository userRepository,
                                    EmailService emailService) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.emailService = emailService;
    }

    /**
     * Creates a verification token and emails it to the newly registered student.
     */
    @Transactional
    public void sendVerificationEmail(User user) {
        // Remove any existing token for this user
        tokenRepository.deleteByUser(user);

        String token = UUID.randomUUID().toString();
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(24);

        EmailVerificationToken evt = new EmailVerificationToken(token, user, expiresAt);
        tokenRepository.save(evt);

        String verifyLink = frontendBaseUrl + "/verify-email?token=" + token;

        String body = "Dear " + user.getFullName() + ",\n\n" +
                "Welcome to the Equipment Request Management System (ERMS) — University of Jaffna, Faculty of Engineering.\n\n" +
                "Your student account has been created. Please verify your email address by clicking the link below:\n\n" +
                verifyLink + "\n\n" +
                "This link will expire in 24 hours.\n\n" +
                "If you did not register for this account, please ignore this email.\n\n" +
                "──────────────────────────────────────\n" +
                "Faculty of Engineering | University of Jaffna\n" +
                "Equipment Request Management System (ERMS)\n" +
                "This is an automated email. Please do not reply.";

        emailService.sendPlainTextEmail(
                user.getEmail(),
                "[ERMS] Verify Your Email Address",
                body
        );
    }

    /**
     * Verifies the token and marks the user's email as verified.
     * Returns the verified user.
     */
    @Transactional
    public User verifyToken(String token) {
        EmailVerificationToken evt = tokenRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired verification link."));

        if (evt.isUsed()) {
            throw new IllegalArgumentException("This verification link has already been used.");
        }

        if (evt.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("This verification link has expired. Please register again or request a new link.");
        }

        User user = evt.getUser();
        user.setEmailVerified(true);
        userRepository.save(user);

        evt.setUsed(true);
        tokenRepository.save(evt);

        return user;
    }

    /**
     * Resend a verification email (e.g. if the user lost the original).
     */
    @Transactional
    public void resendVerificationEmail(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("No account found with this email."));

        if (user.isEmailVerified()) {
            throw new IllegalArgumentException("This email is already verified.");
        }

        sendVerificationEmail(user);
    }
}