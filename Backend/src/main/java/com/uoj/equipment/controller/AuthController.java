package com.uoj.equipment.controller;

import com.uoj.equipment.dto.*;
import com.uoj.equipment.entity.User;
import com.uoj.equipment.enums.Role;
import com.uoj.equipment.repository.UserRepository;
import com.uoj.equipment.config.JwtUtil;
import com.uoj.equipment.service.EmailVerificationService;
import com.uoj.equipment.service.PasswordResetService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final PasswordResetService passwordResetService;
    private final EmailVerificationService emailVerificationService;

    public AuthController(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          AuthenticationManager authenticationManager,
                          JwtUtil jwtUtil,
                          PasswordResetService passwordResetService,
                          EmailVerificationService emailVerificationService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtUtil = jwtUtil;
        this.passwordResetService = passwordResetService;
        this.emailVerificationService = emailVerificationService;
    }

    // ── LOGIN ─────────────────────────────────────────────────────────────────
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginDTO dto) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(dto.getEmail(), dto.getPassword()));

            User user = userRepository.findByEmail(dto.getEmail())
                    .orElseThrow(() -> new IllegalArgumentException("Invalid email or password"));

            if (!user.isEnabled()) {
                return ResponseEntity.status(403).body(Map.of(
                        "error", "ACCOUNT_DISABLED",
                        "message", "Your account has been disabled. Please contact the administrator."
                ));
            }

            /*
             * FIX BUG 2: Enforce email verification for STUDENT accounts.
             *
             * Students sign up via the public form → emailVerified=false until they
             * click the link in their email. The old code let them log in without
             * verifying, making the entire email-verification flow pointless.
             *
             * Admin-created users (HOD, TO, LECTURER, STAFF) have emailVerified=true
             * set by AdminUserService.createUserWithRole(), so they are unaffected.
             *
             * STAFF who self-signup also need to verify — added STAFF check here too.
             */
            if ((user.getRole() == Role.STUDENT || user.getRole() == Role.STAFF)
                    && !user.isEmailVerified()) {
                return ResponseEntity.status(403).body(Map.of(
                        "error", "EMAIL_NOT_VERIFIED",
                        "message", "Please verify your email address before logging in. " +
                                   "Check your inbox for the verification link."
                ));
            }

            String token = jwtUtil.generateToken(user.getEmail());

            LoginResponseDTO resp = new LoginResponseDTO(
                    "Login successful",
                    token,
                    user.getEmail(),
                    user.getRole().name()
            );
            return ResponseEntity.ok(resp);

        } catch (BadCredentialsException ex) {
            return ResponseEntity.status(401).body(Map.of(
                    "error", "BAD_CREDENTIALS",
                    "message", "Invalid email or password"
            ));
        }
    }

    // ── PROFILE ───────────────────────────────────────────────────────────────
    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication auth) {
        User user = userRepository.findByEmail(auth.getName()).orElse(null);
        if (user == null) return ResponseEntity.status(404).body("User not found");

        return ResponseEntity.ok(new SimpleUserDTO(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getRegNo(),
                user.getDepartment(),
                user.getRole().name(),
                user.isEnabled()
        ));
    }

    // ── STUDENT SIGNUP ────────────────────────────────────────────────────────
    @PostMapping("/signup")
    public ResponseEntity<?> signup(@RequestBody SignupRequestDTO dto) {

        if (dto.getFullName() == null || dto.getFullName().isBlank())
            return ResponseEntity.badRequest().body("Full name is required");
        if (dto.getEmail() == null || dto.getEmail().isBlank())
            return ResponseEntity.badRequest().body("Email is required");
        if (dto.getRegNo() == null || dto.getRegNo().isBlank())
            return ResponseEntity.badRequest().body("Register number is required");
        if (dto.getDepartment() == null || dto.getDepartment().isBlank())
            return ResponseEntity.badRequest().body("Department is required");
        if (dto.getPassword() == null || dto.getPassword().isBlank())
            return ResponseEntity.badRequest().body("Password is required");

        if (userRepository.existsByEmail(dto.getEmail()))
            return ResponseEntity.badRequest().body("Email already exists");
        if (userRepository.existsByRegNo(dto.getRegNo()))
            return ResponseEntity.badRequest().body("Register number already exists");

        // Reg no format: 2022/E/063
        String regNoRegex = "^20[0-9]{2}/E/[0-9]{3}$";
        if (!dto.getRegNo().matches(regNoRegex))
            return ResponseEntity.badRequest().body("Invalid register number. Use format: 2022/E/063");

        // Email format: 2022e063@eng.jfn.ac.lk
        String emailRegex = "^20[0-9]{2}e[0-9]{3}@eng\\.jfn\\.ac\\.lk$";
        if (!dto.getEmail().matches(emailRegex))
            return ResponseEntity.badRequest().body("Invalid student email. Use format: 2022e063@eng.jfn.ac.lk");

        // Email must match reg number
        String regNoClean = dto.getRegNo().replace("/", ""); // 2022E063
        String expectedEmailPrefix = regNoClean.substring(0, 4) + "e" + regNoClean.substring(5);
        String actualEmailPrefix = dto.getEmail().split("@")[0];
        if (!expectedEmailPrefix.equalsIgnoreCase(actualEmailPrefix))
            return ResponseEntity.badRequest().body(
                    "Email does not match register number. Expected: " + expectedEmailPrefix + "@eng.jfn.ac.lk");

        // Create user — NOT verified yet
        User user = new User();
        user.setFullName(dto.getFullName());
        user.setEmail(dto.getEmail());
        user.setRegNo(dto.getRegNo());
        user.setDepartment(dto.getDepartment());
        user.setPasswordHash(passwordEncoder.encode(dto.getPassword()));
        user.setRole(Role.STUDENT);
        user.setEnabled(true);
        user.setEmailVerified(false); // must verify before login
        userRepository.save(user);

        // Send verification email
        try {
            emailVerificationService.sendVerificationEmail(user);
        } catch (Exception e) {
            // Don't fail the signup if email delivery fails — user can resend
            System.err.println("[AuthController] Verification email failed: " + e.getMessage());
        }

        return ResponseEntity.ok(Map.of(
                "message", "Registration successful! Please check your email (" + dto.getEmail() + ") " +
                           "and click the verification link to activate your account.",
                "email", dto.getEmail()
        ));
    }

    // ── EMAIL VERIFICATION ────────────────────────────────────────────────────
    /*
     * NOTE: This endpoint must be in SecurityConfig.permitAll()!
     * FIX BUG 8: /api/auth/verify-email and /api/auth/resend-verification were
     * missing from SecurityConfig.permitAll(). See SecurityConfig.java fix.
     */
    @GetMapping("/verify-email")
    public ResponseEntity<?> verifyEmail(@RequestParam String token) {
        try {
            User user = emailVerificationService.verifyToken(token);
            return ResponseEntity.ok(Map.of(
                    "message", "Email verified successfully! You can now log in.",
                    "email", user.getEmail()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── RESEND VERIFICATION ───────────────────────────────────────────────────
    @PostMapping("/resend-verification")
    public ResponseEntity<?> resendVerification(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        if (email == null || email.isBlank())
            return ResponseEntity.badRequest().body("Email is required");
        try {
            emailVerificationService.resendVerificationEmail(email.trim());
            return ResponseEntity.ok(Map.of("message", "Verification email resent. Please check your inbox."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── FORGOT / RESET PASSWORD ───────────────────────────────────────────────
    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestBody ForgotPasswordRequestDTO dto) {
        if (dto.getEmail() == null || dto.getEmail().isBlank())
            return ResponseEntity.badRequest().body("Email is required");
        passwordResetService.createResetTokenForEmail(dto.getEmail().trim());
        return ResponseEntity.ok(Map.of("message", "If this email exists, a reset link has been sent."));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody ResetPasswordDTO dto) {
        if (dto.getToken() == null || dto.getToken().isBlank())
            return ResponseEntity.badRequest().body("Token is required");
        if (dto.getNewPassword() == null || dto.getNewPassword().isBlank())
            return ResponseEntity.badRequest().body("New password is required");
        passwordResetService.resetPassword(dto.getToken().trim(), dto.getNewPassword());
        return ResponseEntity.ok(Map.of("message", "Password has been reset successfully. You may now log in."));
    }

    // ── CHANGE PASSWORD (authenticated) ──────────────────────────────────────
    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(
            @RequestBody ChangePasswordDTO dto,
            @AuthenticationPrincipal UserDetails principalUser) {

        User user = userRepository.findByEmail(principalUser.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!passwordEncoder.matches(dto.getCurrentPassword(), user.getPasswordHash())) {
            return ResponseEntity.badRequest().body("Current password is incorrect");
        }

        user.setPasswordHash(passwordEncoder.encode(dto.getNewPassword()));
        userRepository.save(user);

        return ResponseEntity.ok("Password changed successfully");
    }
}