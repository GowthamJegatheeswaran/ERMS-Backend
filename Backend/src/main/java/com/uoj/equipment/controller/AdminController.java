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
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminUserService adminUserService;
    private final AdminDepartmentService adminDepartmentService;
    private final PurchaseService purchaseService;

    public AdminController(AdminUserService adminUserService,
                           AdminDepartmentService adminDepartmentService,
                           PurchaseService purchaseService) {
        this.adminUserService = adminUserService;
        this.adminDepartmentService = adminDepartmentService;
        this.purchaseService = purchaseService;
    }

    @GetMapping("/departments")
    public ResponseEntity<List<String>> listDepartments(Authentication auth) {
        return ResponseEntity.ok(adminDepartmentService.getDepartments());
    }

    @GetMapping("/departments/{dept}/users")
    public ResponseEntity<AdminDepartmentUsersDTO> getDepartmentUsers(@PathVariable String dept,
                                                                      Authentication auth) {
        AdminDepartmentUsersDTO dto = adminDepartmentService.getDepartmentUsers(dept);
        return ResponseEntity.ok(dto);
    }

    @GetMapping("/departments/{dept}/purchase-requests")
    public ResponseEntity<List<PurchaseRequestSummaryDTO>> getDepartmentPendingPurchases(
            @PathVariable String dept,
            Authentication auth) {
        List<PurchaseRequestSummaryDTO> list =
                adminDepartmentService.getDepartmentPendingPurchases(dept);
        return ResponseEntity.ok(list);
    }

    @GetMapping("/departments/{dept}/purchase-report")
    public ResponseEntity<List<PurchaseRequestSummaryDTO>> getDepartmentPurchaseReport(
            @PathVariable String dept,
            Authentication auth
    ) {
        return ResponseEntity.ok(adminDepartmentService.getDepartmentPurchaseReport(dept));
    }

    @GetMapping("/departments/{dept}/purchase-history")
    public ResponseEntity<List<PurchaseRequestSummaryDTO>> getDepartmentPurchaseHistory(
            @PathVariable String dept,
            Authentication auth
    ) {
        return ResponseEntity.ok(adminDepartmentService.getDepartmentPurchaseHistory(dept));
    }

    @PostMapping("/departments/{dept}/purchase-requests/{id}/approve")
    public ResponseEntity<PurchaseRequestSummaryDTO> approvePurchase(@PathVariable String dept,
                                                                     @PathVariable Long id,
                                                                     @RequestParam(required = false) String comment,
                                                                     @RequestParam(required = false)
                                                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                                                     LocalDate issuedDate,
                                                                     Authentication auth) {

        String adminEmail = auth.getName();

        PurchaseRequestSummaryDTO dto =
                purchaseService.adminDecision(adminEmail, id, true, comment, issuedDate);

        return ResponseEntity.ok(dto);
    }

    @PostMapping("/departments/{dept}/purchase-requests/{id}/reject")
    public ResponseEntity<PurchaseRequestSummaryDTO> rejectPurchase(@PathVariable String dept,
                                                                    @PathVariable Long id,
                                                                    @RequestParam(required = false) String reason,
                                                                    Authentication auth) {

        String adminEmail = auth.getName();

        PurchaseRequestSummaryDTO dto =
                purchaseService.adminDecision(adminEmail, id, false, reason, null);

        return ResponseEntity.ok(dto);
    }

    @PostMapping("/users/hod")
    public ResponseEntity<SimpleUserDTO> createHod(@RequestBody CreateUserRequestDTO dto,
                                                   Authentication auth) {
        User created = adminUserService.createUserWithRole(dto, Role.HOD);
        return ResponseEntity.ok(toSimpleUserDTO(created));
    }

    @PostMapping("/users/lecturer")
    public ResponseEntity<SimpleUserDTO> createLecturer(@RequestBody CreateUserRequestDTO dto,
                                                        Authentication auth) {
        User created = adminUserService.createUserWithRole(dto, Role.LECTURER);
        return ResponseEntity.ok(toSimpleUserDTO(created));
    }

    @PostMapping("/users/staff")
    public ResponseEntity<SimpleUserDTO> createStaff(@RequestBody CreateUserRequestDTO dto,
                                                     Authentication auth) {
        User created = adminUserService.createUserWithRole(dto, Role.STAFF);
        return ResponseEntity.ok(toSimpleUserDTO(created));
    }

    @PostMapping("/users/to")
    public ResponseEntity<SimpleUserDTO> createTo(@RequestBody CreateUserRequestDTO dto,
                                                  Authentication auth) {
        User created = adminUserService.createUserWithRole(dto, Role.TO);
        return ResponseEntity.ok(toSimpleUserDTO(created));
    }

    @PostMapping("/users/student")
    public ResponseEntity<SimpleUserDTO> createStudent(@RequestBody CreateUserRequestDTO dto,
                                                       Authentication auth) {
        User created = adminUserService.createUserWithRole(dto, Role.STUDENT);
        return ResponseEntity.ok(toSimpleUserDTO(created));
    }

    @PutMapping("/users/{id}")
    public ResponseEntity<SimpleUserDTO> updateUser(@PathVariable Long id,
                                                    @RequestBody CreateUserRequestDTO dto,
                                                    Authentication auth) {
        User updated = adminUserService.updateUserBasicInfo(id, dto);
        return ResponseEntity.ok(toSimpleUserDTO(updated));
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<String> deleteUser(@PathVariable Long id,
                                             Authentication auth) {
        adminUserService.deleteUser(id);
        return ResponseEntity.ok("User " + id + " removed by admin");
    }

    @PostMapping("/users/{id}/disable")
    public ResponseEntity<SimpleUserDTO> disableUser(@PathVariable Long id,
                                                     Authentication auth) {
        User updated = adminUserService.disableUser(id);
        return ResponseEntity.ok(toSimpleUserDTO(updated));
    }

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
