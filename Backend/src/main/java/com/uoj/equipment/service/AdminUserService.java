package com.uoj.equipment.service;

import com.uoj.equipment.dto.CreateUserRequestDTO;
import com.uoj.equipment.entity.User;
import com.uoj.equipment.enums.Role;
import com.uoj.equipment.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AdminUserService {

    private static final String DEFAULT_PASSWORD = "Default@123";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final NotificationService notificationService;

    public AdminUserService(UserRepository userRepository,
                            PasswordEncoder passwordEncoder,
                            NotificationService notificationService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.notificationService = notificationService;
    }

    /**
     * Admin creates any user type.
     * Admin-created users are emailVerified=true (no email confirmation required).
     * Default password: Default@123
     */
    public User createUserWithRole(CreateUserRequestDTO dto, Role role) {
        if (userRepository.existsByEmail(dto.getEmail()))
            throw new IllegalArgumentException("Email already exists: " + dto.getEmail());

        User user = new User();
        user.setFullName(dto.getFullName());
        user.setEmail(dto.getEmail());
        user.setDepartment(dto.getDepartment());
        user.setRole(role);
        user.setPasswordHash(passwordEncoder.encode(DEFAULT_PASSWORD));
        user.setEnabled(true);
        user.setEmailVerified(true); // Admin-created users skip email verification

        if (dto.getRegNo() != null && !dto.getRegNo().isBlank())
            user.setRegNo(dto.getRegNo());

        User saved = userRepository.save(user);

        // Notify the new user via email with their credentials
        try {
            String roleLabel = role.name().charAt(0) + role.name().substring(1).toLowerCase();
            notificationService.notifyUser(
                    saved,
                    com.uoj.equipment.enums.NotificationType.REQUEST_SUBMITTED, // generic type
                    "Your ERMS Account Has Been Created",
                    "Dear " + saved.getFullName() + ",\n\n" +
                    "An account has been created for you in the Equipment Request Management System (ERMS) " +
                    "at the Faculty of Engineering, University of Jaffna.\n\n" +
                    "Role: " + roleLabel + "\n" +
                    "Email: " + saved.getEmail() + "\n" +
                    "Temporary Password: " + DEFAULT_PASSWORD + "\n\n" +
                    "Please log in and change your password immediately.\n\n" +
                    "Login at: http://erms.eng.jfn.ac.lk/login",
                    null, null
            );
        } catch (Exception e) {
            System.err.println("[AdminUserService] Welcome email failed: " + e.getMessage());
        }

        return saved;
    }

    public User updateUserBasicInfo(Long id, CreateUserRequestDTO dto) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));
        if (dto.getFullName() != null) user.setFullName(dto.getFullName());
        if (dto.getDepartment() != null) user.setDepartment(dto.getDepartment());
        if (dto.getRegNo() != null) user.setRegNo(dto.getRegNo());
        return userRepository.save(user);
    }

    public void deleteUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));
        userRepository.delete(user);
    }

    public User disableUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));
        user.setEnabled(false);
        return userRepository.save(user);
    }
}