package com.uoj.equipment.service;

import com.uoj.equipment.dto.CreateUserRequestDTO;
import com.uoj.equipment.entity.Notification;
import com.uoj.equipment.entity.PasswordResetToken;
import com.uoj.equipment.entity.User;
import com.uoj.equipment.enums.Role;
import com.uoj.equipment.repository.EquipmentRequestRepository;
import com.uoj.equipment.repository.NotificationRepository;
import com.uoj.equipment.repository.PasswordResetTokenRepository;
import com.uoj.equipment.repository.PurchaseRequestRepository;
import com.uoj.equipment.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AdminUserService {

    private static final String DEFAULT_PASSWORD = "Default@123";

    private final UserRepository              userRepository;
    private final PasswordEncoder             passwordEncoder;
    private final NotificationService         notificationService;
    private final EquipmentRequestRepository  requestRepository;
    private final PurchaseRequestRepository   purchaseRequestRepository;
    private final NotificationRepository      notificationRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;

    public AdminUserService(UserRepository userRepository,
                            PasswordEncoder passwordEncoder,
                            NotificationService notificationService,
                            EquipmentRequestRepository requestRepository,
                            PurchaseRequestRepository purchaseRequestRepository,
                            NotificationRepository notificationRepository,
                            PasswordResetTokenRepository passwordResetTokenRepository) {
        this.userRepository               = userRepository;
        this.passwordEncoder              = passwordEncoder;
        this.notificationService          = notificationService;
        this.requestRepository            = requestRepository;
        this.purchaseRequestRepository    = purchaseRequestRepository;
        this.notificationRepository       = notificationRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
    }

    // ─────────────────────────────────────────────────────────────
    // CREATE
    // ─────────────────────────────────────────────────────────────

    @Transactional
    public User createUserWithRole(CreateUserRequestDTO dto, Role role) {
        if (userRepository.existsByEmail(dto.getEmail()))
            throw new IllegalArgumentException("Email already registered: " + dto.getEmail());

        if (dto.getRegNo() != null && !dto.getRegNo().isBlank()
                && userRepository.existsByRegNo(dto.getRegNo()))
            throw new IllegalArgumentException("Registration number already exists: " + dto.getRegNo());

        // ONE HOD per department
        if (role == Role.HOD) {
            boolean hodExists = userRepository
                    .findByDepartmentAndRole(dto.getDepartment(), Role.HOD)
                    .isPresent();
            if (hodExists)
                throw new IllegalArgumentException(
                        "Department " + dto.getDepartment() +
                        " already has an HOD. Remove or disable the existing HOD first.");
        }

        User user = new User();
        user.setFullName(dto.getFullName());
        user.setEmail(dto.getEmail());
        user.setDepartment(dto.getDepartment());
        user.setRole(role);
        user.setPasswordHash(passwordEncoder.encode(DEFAULT_PASSWORD));
        user.setEnabled(true);
        user.setEmailVerified(true);

        if (dto.getRegNo() != null && !dto.getRegNo().isBlank())
            user.setRegNo(dto.getRegNo());

        User saved = userRepository.save(user);

        try {
            String roleLabel = role.name().charAt(0) + role.name().substring(1).toLowerCase();
            notificationService.notifyUser(saved,
                    com.uoj.equipment.enums.NotificationType.REQUEST_SUBMITTED,
                    "Your ERMS Account Has Been Created",
                    "Dear " + saved.getFullName() + ",\n\nAn account has been created for you.\n" +
                    "Role: " + roleLabel + "\nEmail: " + saved.getEmail() +
                    "\nTemporary Password: " + DEFAULT_PASSWORD +
                    "\n\nPlease log in and change your password immediately.",
                    null, null);
        } catch (Exception e) {
            System.err.println("[AdminUserService] Welcome notification failed: " + e.getMessage());
        }

        return saved;
    }

    // ─────────────────────────────────────────────────────────────
    // UPDATE
    // ─────────────────────────────────────────────────────────────

    @Transactional
    public User updateUserBasicInfo(Long id, CreateUserRequestDTO dto) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));

        if (dto.getEmail() != null && !dto.getEmail().isBlank()) {
            if (userRepository.existsByEmailAndIdNot(dto.getEmail(), id))
                throw new IllegalArgumentException("Email already in use: " + dto.getEmail());
            user.setEmail(dto.getEmail());
        }
        if (dto.getRegNo() != null && !dto.getRegNo().isBlank()) {
            if (userRepository.existsByRegNoAndIdNot(dto.getRegNo(), id))
                throw new IllegalArgumentException("Registration number already in use: " + dto.getRegNo());
            user.setRegNo(dto.getRegNo());
        }
        if (dto.getFullName() != null && !dto.getFullName().isBlank())
            user.setFullName(dto.getFullName());
        if (dto.getDepartment() != null && !dto.getDepartment().isBlank())
            user.setDepartment(dto.getDepartment());

        return userRepository.save(user);
    }

    // ─────────────────────────────────────────────────────────────
    // DELETE  —  safe for ALL roles
    // ─────────────────────────────────────────────────────────────

    /**
     * Checks every FK reference before deleting:
     *
     *   STUDENT / STAFF  →  equipment_requests.requester_id
     *   LECTURER / HOD   →  equipment_requests.lecturer_id
     *   HOD              →  purchase_requests.hod_id
     *   TO               →  purchase_requests.to_id
     *
     * If linked records exist → 409 with clear message.
     * Notifications + password-reset tokens have no business value → deleted automatically.
     */
    @Transactional
    public void deleteUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));

        Role role = user.getRole();

        // STUDENT or STAFF — submitted requests
        if (role == Role.STUDENT || role == Role.STAFF) {
            int n = requestRepository.findByRequesterIdOrderByIdDesc(id).size();
            if (n > 0)
                throw new IllegalStateException(
                        "Cannot delete " + user.getFullName() +
                        " — they have " + n + " equipment request(s) on record. " +
                        "Disable the account instead.");
        }

        // LECTURER or HOD — used as approving lecturer on requests
        if (role == Role.LECTURER || role == Role.HOD) {
            int n = requestRepository.findByLecturerId(id).size();
            if (n > 0)
                throw new IllegalStateException(
                        "Cannot delete " + user.getFullName() +
                        " — they are the approver on " + n + " request(s). " +
                        "Disable the account instead.");
        }

        // HOD — linked to purchase requests
        if (role == Role.HOD) {
            int n = purchaseRequestRepository.findByHodUserOrderByCreatedDateDesc(user).size();
            if (n > 0)
                throw new IllegalStateException(
                        "Cannot delete this HOD — they have " + n +
                        " purchase request(s) on record. Disable the account instead.");
        }

        // TO — linked to purchase requests
        if (role == Role.TO) {
            int n = purchaseRequestRepository.findByToUserOrderByCreatedDateDesc(user).size();
            if (n > 0)
                throw new IllegalStateException(
                        "Cannot delete " + user.getFullName() +
                        " — they are linked as Technical Officer on " + n +
                        " purchase request(s). Disable the account instead.");
        }

        // Safe to delete — remove orphaned soft-data first
        List<Notification> notifs = notificationRepository.findByUserOrderByCreatedDateDesc(user);
        if (!notifs.isEmpty()) notificationRepository.deleteAll(notifs);

        List<PasswordResetToken> tokens = passwordResetTokenRepository.findAll()
                .stream()
                .filter(t -> t.getUser() != null && t.getUser().getId().equals(id))
                .toList();
        if (!tokens.isEmpty()) passwordResetTokenRepository.deleteAll(tokens);

        userRepository.delete(user);
    }

    // ─────────────────────────────────────────────────────────────
    // DISABLE / ENABLE
    // ─────────────────────────────────────────────────────────────

    @Transactional
    public User disableUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));
        if (!user.isEnabled())
            throw new IllegalStateException("User is already disabled.");
        user.setEnabled(false);
        return userRepository.save(user);
    }

    @Transactional
    public User enableUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));
        if (user.isEnabled())
            throw new IllegalStateException("User account is already active.");
        user.setEnabled(true);
        return userRepository.save(user);
    }
}