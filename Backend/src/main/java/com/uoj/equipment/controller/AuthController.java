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

    private final UserRepository           userRepository;
    private final PasswordEncoder          passwordEncoder;
    private final AuthenticationManager    authenticationManager;
    private final JwtUtil                  jwtUtil;
    private final PasswordResetService     passwordResetService;
    private final EmailVerificationService emailVerificationService;

    public AuthController(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          AuthenticationManager authenticationManager,
                          JwtUtil jwtUtil,
                          PasswordResetService passwordResetService,
                          EmailVerificationService emailVerificationService) {
        this.userRepository           = userRepository;
        this.passwordEncoder          = passwordEncoder;
        this.authenticationManager    = authenticationManager;
        this.jwtUtil                  = jwtUtil;
        this.passwordResetService     = passwordResetService;
        this.emailVerificationService = emailVerificationService;
    }

    // ── LOGIN ─────────────────────────────────────────────────────────────────
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginDTO dto) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            dto.getEmail(), dto.getPassword()));

            User user = userRepository.findByEmail(dto.getEmail())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Invalid email or password"));

            if (!user.isEnabled())
                return ResponseEntity.status(403).body(Map.of(
                        "error",   "ACCOUNT_DISABLED",
                        "message", "Your account has been disabled. Please contact the administrator."));

            String token = jwtUtil.generateToken(user.getEmail());
            return ResponseEntity.ok(new LoginResponseDTO(
                    "Login successful", token,
                    user.getEmail(), user.getRole().name()));

        } catch (BadCredentialsException ex) {
            return ResponseEntity.status(401).body(Map.of(
                    "error",   "BAD_CREDENTIALS",
                    "message", "Invalid email or password"));
        }
    }

    // ── PROFILE ───────────────────────────────────────────────────────────────
    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication auth) {
        User user = userRepository.findByEmail(auth.getName()).orElse(null);
        if (user == null) return ResponseEntity.status(404).body("User not found");
        return ResponseEntity.ok(new SimpleUserDTO(
                user.getId(), user.getFullName(), user.getEmail(),
                user.getRegNo(), user.getDepartment(),
                user.getRole().name(), user.isEnabled()));
    }

    // ── STUDENT SIGNUP ────────────────────────────────────────────────────────
    @PostMapping("/signup")
    public ResponseEntity<?> signup(@RequestBody SignupRequestDTO dto) {

        if (dto.getFullName()   == null || dto.getFullName().isBlank())
            return ResponseEntity.badRequest().body("Full name is required");
        if (dto.getEmail()      == null || dto.getEmail().isBlank())
            return ResponseEntity.badRequest().body("Email is required");
        if (dto.getRegNo()      == null || dto.getRegNo().isBlank())
            return ResponseEntity.badRequest().body("Register number is required");
        if (dto.getDepartment() == null || dto.getDepartment().isBlank())
            return ResponseEntity.badRequest().body("Department is required");
        if (dto.getPassword()   == null || dto.getPassword().isBlank())
            return ResponseEntity.badRequest().body("Password is required");

        if (userRepository.existsByEmail(dto.getEmail()))
            return ResponseEntity.badRequest().body("Email already exists");
        if (userRepository.existsByRegNo(dto.getRegNo()))
            return ResponseEntity.badRequest().body("Register number already exists");

        if (!dto.getRegNo().matches("^20[0-9]{2}/E/[0-9]{3}$"))
            return ResponseEntity.badRequest()
                    .body("Invalid register number. Use format: 2022/E/063");

        if (!dto.getEmail().matches("^20[0-9]{2}e[0-9]{3}@eng\\.jfn\\.ac\\.lk$"))
            return ResponseEntity.badRequest()
                    .body("Invalid student email. Use format: 2022e063@eng.jfn.ac.lk");

        String regNoClean          = dto.getRegNo().replace("/", "");
        String expectedEmailPrefix = regNoClean.substring(0, 4) + "e" + regNoClean.substring(5);
        String actualEmailPrefix   = dto.getEmail().split("@")[0];
        if (!expectedEmailPrefix.equalsIgnoreCase(actualEmailPrefix))
            return ResponseEntity.badRequest().body(
                    "Email does not match register number. Expected: "
                    + expectedEmailPrefix + "@eng.jfn.ac.lk");

        User user = new User();
        user.setFullName(dto.getFullName());
        user.setEmail(dto.getEmail());
        user.setRegNo(dto.getRegNo());
        user.setDepartment(dto.getDepartment());
        user.setPasswordHash(passwordEncoder.encode(dto.getPassword()));
        user.setRole(Role.STUDENT);
        user.setEnabled(true);
        user.setEmailVerified(false);
        userRepository.save(user);

        try {
            emailVerificationService.sendVerificationEmail(user);
        } catch (Exception e) {
            System.err.println("[AuthController] Verification email failed: "
                    + e.getMessage());
        }

        return ResponseEntity.ok(Map.of(
                "message", "Registration successful! Please check your email ("
                           + dto.getEmail() + ") and click the verification link.",
                "email", dto.getEmail()));
    }

    // ── EMAIL VERIFICATION ────────────────────────────────────────────────────
    @GetMapping("/verify-email")
    public ResponseEntity<?> verifyEmail(@RequestParam String token) {
        try {
            User user = emailVerificationService.verifyToken(token);
            return ResponseEntity.ok(Map.of(
                    "message", "Email verified successfully! You can now log in.",
                    "email",   user.getEmail()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<?> resendVerification(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        if (email == null || email.isBlank())
            return ResponseEntity.badRequest().body("Email is required");
        try {
            emailVerificationService.resendVerificationEmail(email.trim());
            return ResponseEntity.ok(Map.of(
                    "message", "Verification email resent. Please check your inbox."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── FORGOT PASSWORD — Step 1: send OTP ───────────────────────────────────
    //   POST /api/auth/forgot-password   { "email": "..." }
    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestBody ForgotPasswordRequestDTO dto) {
        if (dto.getEmail() == null || dto.getEmail().isBlank())
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Email is required"));
        try {
            passwordResetService.sendOtpForEmail(dto.getEmail().trim());
            // Always return 200 (security: don't reveal if email exists)
            return ResponseEntity.ok(Map.of(
                    "message",
                    "If this email is registered, a 6-digit OTP has been sent. Check your inbox."));
        } catch (IllegalArgumentException e) {
            // Email not found — still return 200 for security (don't reveal existence)
            System.err.println("[Auth] OTP skipped: " + e.getMessage());
            return ResponseEntity.ok(Map.of(
                    "message",
                    "If this email is registered, a 6-digit OTP has been sent. Check your inbox."));
        } catch (Exception e) {
            // EMAIL SEND FAILED — expose error so we can diagnose
            System.err.println("[Auth] OTP send FAILED: " + e.getMessage());
            return ResponseEntity.status(500).body(Map.of(
                    "message", "Failed to send OTP email. Please try again later.",
                    "error",   e.getMessage()   // <-- remove this line in production once fixed
            ));
        }
    }

    // ── RESET PASSWORD — Step 2: verify OTP + set new password ───────────────
    //   POST /api/auth/reset-password   { "email": "...", "otp": "482931", "newPassword": "..." }
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody ResetPasswordDTO dto) {
        if (dto.getEmail()       == null || dto.getEmail().isBlank())
            return ResponseEntity.badRequest().body(Map.of("message", "Email is required"));
        if (dto.getOtp()         == null || dto.getOtp().isBlank())
            return ResponseEntity.badRequest().body(Map.of("message", "OTP is required"));
        if (dto.getNewPassword() == null || dto.getNewPassword().isBlank())
            return ResponseEntity.badRequest().body(Map.of("message", "New password is required"));

        passwordResetService.resetPasswordWithOtp(
                dto.getEmail().trim(),
                dto.getOtp().trim(),
                dto.getNewPassword());

        return ResponseEntity.ok(Map.of(
                "message", "Password reset successfully. You can now log in."));
    }

    // ── CHANGE PASSWORD (authenticated) ──────────────────────────────────────
    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(
            @RequestBody ChangePasswordDTO dto,
            @AuthenticationPrincipal UserDetails principalUser) {

        User user = userRepository.findByEmail(principalUser.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!passwordEncoder.matches(dto.getCurrentPassword(), user.getPasswordHash()))
            return ResponseEntity.badRequest().body("Current password is incorrect");

        user.setPasswordHash(passwordEncoder.encode(dto.getNewPassword()));
        userRepository.save(user);
        return ResponseEntity.ok("Password changed successfully");
    }
}