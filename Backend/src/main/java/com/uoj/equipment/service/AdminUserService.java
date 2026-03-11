package com.uoj.equipment.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.uoj.equipment.dto.CreateUserRequestDTO;
import com.uoj.equipment.entity.User;
import com.uoj.equipment.enums.Role;
import com.uoj.equipment.repository.UserRepository;
import com.uoj.equipment.util.DepartmentUtil;

@Service
public class AdminUserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminUserService(UserRepository userRepository,
                            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public User createUserWithRole(CreateUserRequestDTO dto, Role role) {
        if (role == Role.STUDENT) {
            throw new IllegalArgumentException("Admin cannot create students from user management");
        }

        String email = normalizeEmail(dto.getEmail());
        String department = DepartmentUtil.normalize(dto.getDepartment());

        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email is required");
        }
        if (dto.getFullName() == null || dto.getFullName().isBlank()) {
            throw new IllegalArgumentException("Full name is required");
        }
        if (department == null || department.isBlank()) {
            throw new IllegalArgumentException("Department is required");
        }

        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("Email already exists: " + email);
        }

        if (role == Role.HOD && hasDepartmentHod(department)) {
            throw new IllegalArgumentException("This department already has one HOD. Don't add more than one.");
        }

        User u = new User();
        u.setFullName(dto.getFullName().trim());
        u.setEmail(email);
        u.setDepartment(department);
        u.setRole(role);

        if (role == Role.STUDENT) {
            String regNo = normalizeRegNo(dto.getRegNo());
            if (regNo == null || regNo.isBlank()) {
                throw new IllegalArgumentException("regNo required for STUDENT");
            }
            if (userRepository.existsByRegNo(regNo)) {
                throw new IllegalArgumentException("Registration number already exists: " + regNo);
            }
            u.setRegNo(regNo);
        }

        String password = (dto.getInitialPassword() == null || dto.getInitialPassword().isBlank())
                ? "Default@123"
                : dto.getInitialPassword();

        u.setPasswordHash(passwordEncoder.encode(password));
        u.setEnabled(true);

        return userRepository.save(u);
    }

    @Transactional
    public User updateUserBasicInfo(Long id, CreateUserRequestDTO dto) {
        User u = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (u.getRole() == Role.STUDENT) {
            if (dto.getFullName() != null && !dto.getFullName().isBlank()) {
                u.setFullName(dto.getFullName().trim());
            }
            if (dto.getRegNo() != null) {
                String regNo = normalizeRegNo(dto.getRegNo());
                if (regNo == null || regNo.isBlank()) {
                    throw new IllegalArgumentException("Registration number is required");
                }
                if (userRepository.existsByRegNoAndIdNot(regNo, u.getId())) {
                    throw new IllegalArgumentException("Registration number already exists: " + regNo);
                }
                u.setRegNo(regNo);
            }
            return userRepository.save(u);
        }

        if (dto.getFullName() != null && !dto.getFullName().isBlank()) {
            u.setFullName(dto.getFullName().trim());
        }

        if (dto.getEmail() != null) {
            String email = normalizeEmail(dto.getEmail());
            if (email == null || email.isBlank()) {
                throw new IllegalArgumentException("Email is required");
            }
            if (userRepository.existsByEmailAndIdNot(email, u.getId())) {
                throw new IllegalArgumentException("Email already exists: " + email);
            }
            u.setEmail(email);
        }

        if (dto.getDepartment() != null) {
            String newDepartment = DepartmentUtil.normalize(dto.getDepartment());
            if (newDepartment == null || newDepartment.isBlank()) {
                throw new IllegalArgumentException("Department is required");
            }
            if (u.getRole() == Role.HOD && !DepartmentUtil.equalsNormalized(u.getDepartment(), newDepartment) && hasDepartmentHod(newDepartment)) {
                throw new IllegalArgumentException("This department already has one HOD. Don't add more than one.");
            }
            u.setDepartment(newDepartment);
        }

        if (dto.getEnabled() != null) {
            u.setEnabled(dto.getEnabled());
        }

        return userRepository.save(u);
    }

    @Transactional
    public void deleteUser(Long id) {
        User u = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        userRepository.delete(u);
    }

    @Transactional
    public User disableUser(Long id) {
        User u = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (u.getRole() == Role.STUDENT) {
            throw new IllegalArgumentException("Disable/enable is not available for students");
        }

        u.setEnabled(!u.isEnabled());
        return userRepository.save(u);
    }

    private boolean hasDepartmentHod(String department) {
        return userRepository
                .findByDepartmentInAndRoleOrderByFullNameAsc(DepartmentUtil.aliasesForQuery(department), Role.HOD)
                .stream()
                .findAny()
                .isPresent();
    }

    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    private String normalizeRegNo(String regNo) {
        return regNo == null ? null : regNo.trim().toUpperCase();
    }
}
