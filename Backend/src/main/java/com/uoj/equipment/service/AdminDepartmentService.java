package com.uoj.equipment.service;

import com.uoj.equipment.dto.AdminDepartmentUsersDTO;
import com.uoj.equipment.dto.PurchaseRequestSummaryDTO;
import com.uoj.equipment.dto.SimpleUserDTO;
import com.uoj.equipment.entity.PurchaseItem;
import com.uoj.equipment.entity.PurchaseRequest;
import com.uoj.equipment.entity.User;
import com.uoj.equipment.enums.PurchaseStatus;
import com.uoj.equipment.enums.Role;
import com.uoj.equipment.repository.PurchaseRequestRepository;
import com.uoj.equipment.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class AdminDepartmentService {

    private final UserRepository userRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;

    public AdminDepartmentService(UserRepository userRepository,
                                  PurchaseRequestRepository purchaseRequestRepository) {
        this.userRepository = userRepository;
        this.purchaseRequestRepository = purchaseRequestRepository;
    }

    public List<String> getDepartments() {
        return List.of("CE", "EEE");
    }

    public AdminDepartmentUsersDTO getDepartmentUsers(String department) {

        List<SimpleUserDTO> hods = mapUsers(
                userRepository.findByDepartmentInAndRoleOrderByFullNameAsc(com.uoj.equipment.util.DepartmentUtil.aliasesForQuery(department), Role.HOD));

        List<SimpleUserDTO> tos = mapUsers(
                userRepository.findByDepartmentInAndRoleOrderByFullNameAsc(com.uoj.equipment.util.DepartmentUtil.aliasesForQuery(department), Role.TO));

        List<SimpleUserDTO> lecturers = mapUsers(
                userRepository.findByDepartmentInAndRoleOrderByFullNameAsc(com.uoj.equipment.util.DepartmentUtil.aliasesForQuery(department), Role.LECTURER));

        List<SimpleUserDTO> staff = mapUsers(
                userRepository.findByDepartmentInAndRoleOrderByFullNameAsc(com.uoj.equipment.util.DepartmentUtil.aliasesForQuery(department), Role.STAFF));

        List<SimpleUserDTO> students = mapUsers(
                userRepository.findByDepartmentInAndRoleOrderByFullNameAsc(com.uoj.equipment.util.DepartmentUtil.aliasesForQuery(department), Role.STUDENT));

        return new AdminDepartmentUsersDTO(
                department,
                hods,
                tos,
                lecturers,
                staff,
                students
        );
    }

    private List<SimpleUserDTO> mapUsers(List<User> users) {
        return users.stream()
                .map(u -> new SimpleUserDTO(
                        u.getId(),
                        u.getFullName(),
                        u.getEmail(),
                        u.getRegNo(),
                        u.getDepartment(),
                        u.getRole().name(),
                        u.isEnabled()
                ))
                .collect(Collectors.toList());
    }

    public List<PurchaseRequestSummaryDTO> getDepartmentPendingPurchases(String department) {
        List<String> depts = com.uoj.equipment.util.DepartmentUtil.aliasesForQuery(department);
        List<PurchaseRequest> list = purchaseRequestRepository
                .findByDepartmentInAndStatusOrderByCreatedDateDesc(
                        depts, PurchaseStatus.APPROVED_BY_HOD);

        return list.stream()
                .map(this::mapToSummary)
                .collect(Collectors.toList());
    }

    public List<PurchaseRequestSummaryDTO> getDepartmentPurchaseReport(String department) {
        List<String> depts = com.uoj.equipment.util.DepartmentUtil.aliasesForQuery(department);

        List<PurchaseRequest> out = new java.util.ArrayList<>();
        out.addAll(purchaseRequestRepository.findByDepartmentInAndStatusOrderByCreatedDateDesc(depts, PurchaseStatus.ISSUED_BY_ADMIN));
        out.addAll(purchaseRequestRepository.findByDepartmentInAndStatusOrderByCreatedDateDesc(depts, PurchaseStatus.RECEIVED_BY_HOD));
        out.addAll(purchaseRequestRepository.findByDepartmentInAndStatusOrderByCreatedDateDesc(depts, PurchaseStatus.REJECTED_BY_HOD));
        out.addAll(purchaseRequestRepository.findByDepartmentInAndStatusOrderByCreatedDateDesc(depts, PurchaseStatus.REJECTED_BY_ADMIN));

        try { out.addAll(purchaseRequestRepository.findByDepartmentInAndStatusOrderByCreatedDateDesc(depts, PurchaseStatus.APPROVED_BY_ADMIN)); } catch (Exception ignored) {}
        try { out.addAll(purchaseRequestRepository.findByDepartmentInAndStatusOrderByCreatedDateDesc(depts, PurchaseStatus.RECEIVED_BY_TO)); } catch (Exception ignored) {}

        java.util.Map<Long, PurchaseRequest> uniq = new java.util.LinkedHashMap<>();
        for (PurchaseRequest pr : out) {
            if (pr != null && pr.getId() != null) uniq.putIfAbsent(pr.getId(), pr);
        }
        return uniq.values().stream()
                .sorted((a,b) -> {
                    java.time.LocalDate da = a.getCreatedDate();
                    java.time.LocalDate db = b.getCreatedDate();
                    if (da == null && db == null) return 0;
                    if (da == null) return 1;
                    if (db == null) return -1;
                    return db.compareTo(da);
                })
                .map(this::mapToSummary)
                .collect(java.util.stream.Collectors.toList());
    }

    public List<PurchaseRequestSummaryDTO> getDepartmentPurchaseHistory(String department) {
        return getDepartmentPurchaseReport(department);
    }

    private PurchaseRequestSummaryDTO mapToSummary(PurchaseRequest pr) {
        List<PurchaseRequestSummaryDTO.ItemLine> itemLines = pr.getItems()
                .stream()
                .map(this::mapItem)
                .collect(Collectors.toList());

        PurchaseRequestSummaryDTO dto = new PurchaseRequestSummaryDTO();
        dto.setId(pr.getId());
        dto.setDepartment(pr.getDepartment());
        dto.setStatus(pr.getStatus());
        dto.setReason(pr.getReason());
        dto.setCreatedDate(pr.getCreatedDate());
        dto.setIssuedDate(pr.getIssuedDate());
        dto.setReceivedDate(pr.getReceivedDate());
        if (pr.getHodUser()!= null) {
            dto.setRequestedByName(pr.getHodUser().getFullName());
            dto.setRequestedByEmail(pr.getHodUser().getEmail());
        }

        dto.setItems(itemLines);
        return dto;
    }

    private PurchaseRequestSummaryDTO.ItemLine mapItem(PurchaseItem pi) {
        return new PurchaseRequestSummaryDTO.ItemLine(
                pi.getEquipment().getName(),
                pi.getQuantityRequested()
        );
    }
}
