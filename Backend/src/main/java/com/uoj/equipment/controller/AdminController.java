package com.uoj.equipment.controller;

import com.uoj.equipment.dto.AdminDepartmentUsersDTO;
import com.uoj.equipment.dto.CreateUserRequestDTO;
import com.uoj.equipment.dto.PurchaseRequestSummaryDTO;
import com.uoj.equipment.dto.SimpleUserDTO;
import com.uoj.equipment.entity.User;
import com.uoj.equipment.enums.Role;
import com.uoj.equipment.service.AdminDepartmentService;
import com.uoj.equipment.service.AdminUserService;
import com.uoj.equipment.service.PurchaseService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminUserService adminUserService;
    private final AdminDepartmentService adminDepartmentService;
    private final PurchaseService purchaseService;

    public AdminController(AdminUserService adminUserService,
                           AdminDepartmentService adminDepartmentService,
                           PurchaseService purchaseService) {
        this.adminUserService       = adminUserService;
        this.adminDepartmentService = adminDepartmentService;
        this.purchaseService        = purchaseService;
    }

    // ── Departments ──────────────────────────────────────────────

    @GetMapping("/departments")
    public ResponseEntity<List<String>> listDepartments(Authentication auth) {
        return ResponseEntity.ok(adminDepartmentService.getDepartments());
    }

    @GetMapping("/departments/{dept}/users")
    public ResponseEntity<AdminDepartmentUsersDTO> getDepartmentUsers(@PathVariable String dept) {
        return ResponseEntity.ok(adminDepartmentService.getDepartmentUsers(dept));
    }

    @GetMapping("/departments/{dept}/purchase-requests")
    public ResponseEntity<List<PurchaseRequestSummaryDTO>> getDepartmentPendingPurchases(
            @PathVariable String dept) {
        return ResponseEntity.ok(adminDepartmentService.getDepartmentPendingPurchases(dept));
    }

    @GetMapping("/departments/{dept}/purchase-report")
    public ResponseEntity<List<PurchaseRequestSummaryDTO>> getDepartmentPurchaseReport(
            @PathVariable String dept) {
        return ResponseEntity.ok(adminDepartmentService.getDepartmentPurchaseReport(dept));
    }

    @GetMapping("/departments/{dept}/purchase-history")
    public ResponseEntity<List<PurchaseRequestSummaryDTO>> getDepartmentPurchaseHistory(
            @PathVariable String dept) {
        return ResponseEntity.ok(adminDepartmentService.getDepartmentPurchaseHistory(dept));
    }

    @PostMapping("/departments/{dept}/purchase-requests/{id}/approve")
    public ResponseEntity<PurchaseRequestSummaryDTO> approvePurchase(
            @PathVariable String dept,
            @PathVariable Long id,
            @RequestParam(required = false) String comment,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate issuedDate,
            Authentication auth) {
        return ResponseEntity.ok(
                purchaseService.adminDecision(auth.getName(), id, true, comment, issuedDate));
    }

    @PostMapping("/departments/{dept}/purchase-requests/{id}/reject")
    public ResponseEntity<PurchaseRequestSummaryDTO> rejectPurchase(
            @PathVariable String dept,
            @PathVariable Long id,
            @RequestParam(required = false) String reason,
            Authentication auth) {
        return ResponseEntity.ok(
                purchaseService.adminDecision(auth.getName(), id, false, reason, null));
    }

    // ── Create Users ─────────────────────────────────────────────

    @PostMapping("/users/hod")
    public ResponseEntity<?> createHod(@RequestBody CreateUserRequestDTO dto, Authentication auth) {
        return createUser(dto, Role.HOD);
    }

    @PostMapping("/users/lecturer")
    public ResponseEntity<?> createLecturer(@RequestBody CreateUserRequestDTO dto, Authentication auth) {
        return createUser(dto, Role.LECTURER);
    }

    @PostMapping("/users/staff")
    public ResponseEntity<?> createStaff(@RequestBody CreateUserRequestDTO dto, Authentication auth) {
        return createUser(dto, Role.STAFF);
    }

    @PostMapping("/users/to")
    public ResponseEntity<?> createTo(@RequestBody CreateUserRequestDTO dto, Authentication auth) {
        return createUser(dto, Role.TO);
    }

    @PostMapping("/users/student")
    public ResponseEntity<?> createStudent(@RequestBody CreateUserRequestDTO dto, Authentication auth) {
        return createUser(dto, Role.STUDENT);
    }

    private ResponseEntity<?> createUser(CreateUserRequestDTO dto, Role role) {
        try {
            User created = adminUserService.createUserWithRole(dto, role);
            return ResponseEntity.ok(toSimpleUserDTO(created));
        } catch (IllegalArgumentException e) {
            // Validation errors — 400 with message so frontend can display them
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Update User ───────────────────────────────────────────────

    @PutMapping("/users/{id}")
    public ResponseEntity<?> updateUser(@PathVariable Long id,
                                        @RequestBody CreateUserRequestDTO dto,
                                        Authentication auth) {
        try {
            User updated = adminUserService.updateUserBasicInfo(id, dto);
            return ResponseEntity.ok(toSimpleUserDTO(updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Delete User ───────────────────────────────────────────────

    @DeleteMapping("/users/{id}")
    public ResponseEntity<?> deleteUser(@PathVariable Long id, Authentication auth) {
        try {
            adminUserService.deleteUser(id);
            return ResponseEntity.ok(Map.of("message", "User " + id + " deleted successfully."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            // User has linked records — tell frontend to disable instead
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    // ── Disable / Enable ─────────────────────────────────────────

    @PostMapping("/users/{id}/disable")
    public ResponseEntity<?> disableUser(@PathVariable Long id, Authentication auth) {
        try {
            User updated = adminUserService.disableUser(id);
            return ResponseEntity.ok(toSimpleUserDTO(updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/users/{id}/enable")
    public ResponseEntity<?> enableUser(@PathVariable Long id, Authentication auth) {
        try {
            User updated = adminUserService.enableUser(id);
            return ResponseEntity.ok(toSimpleUserDTO(updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Helper ────────────────────────────────────────────────────

    private SimpleUserDTO toSimpleUserDTO(User u) {
        return new SimpleUserDTO(
                u.getId(),
                u.getFullName(),
                u.getEmail(),
                u.getRegNo(),
                u.getDepartment(),
                u.getRole().name(),
                u.isEnabled()
        );
    }
}