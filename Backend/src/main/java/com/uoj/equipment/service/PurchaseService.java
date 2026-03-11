package com.uoj.equipment.service;
import com.uoj.equipment.dto.*;
import com.uoj.equipment.entity.Equipment;
import com.uoj.equipment.entity.PurchaseItem;
import com.uoj.equipment.entity.PurchaseRequest;
import com.uoj.equipment.entity.User;
import com.uoj.equipment.enums.NotificationType;
import com.uoj.equipment.enums.PurchaseStatus;
import com.uoj.equipment.enums.Role;
import com.uoj.equipment.repository.EquipmentRepository;
import com.uoj.equipment.repository.PurchaseItemRepository;
import com.uoj.equipment.repository.PurchaseRequestRepository;
import com.uoj.equipment.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
public class PurchaseService {

    private final UserRepository userRepository;
    private final EquipmentRepository equipmentRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final PurchaseItemRepository purchaseItemRepository;
    private final NotificationService notificationService;

    public PurchaseService(UserRepository userRepository,
                           EquipmentRepository equipmentRepository,
                           PurchaseRequestRepository purchaseRequestRepository,
                           PurchaseItemRepository purchaseItemRepository,
                           NotificationService notificationService) {
        this.userRepository = userRepository;
        this.equipmentRepository = equipmentRepository;
        this.purchaseRequestRepository = purchaseRequestRepository;
        this.purchaseItemRepository = purchaseItemRepository;
        this.notificationService = notificationService;
    }

    // ── Mappers ───────────────────────────────────────────────────────────────

    private HodPurchaseRequestDTO mapToHodDto(PurchaseRequest pr) {
        // Load via repository — avoids lazy-collection issues
        List<PurchaseItem> items = purchaseItemRepository.findByPurchaseRequestId(pr.getId());
        var itemDtos = items.stream()
                .filter(pi -> pi.getEquipment() != null)
                .map(pi -> new HodPurchaseItemDTO(
                        pi.getEquipment().getId(),
                        pi.getEquipment().getName(),
                        pi.getQuantityRequested(),
                        pi.getRemark()
                ))
                .toList();

        return new HodPurchaseRequestDTO(
                pr.getId(),
                pr.getDepartment(),
                pr.getToUser() != null ? pr.getToUser().getFullName() : null,
                pr.getCreatedDate(),
                pr.getStatus(),
                pr.getReason(),
                itemDtos
        );
    }

    /**
     * Always call with a pre-loaded items list from purchaseItemRepository.
     * Never pass pr.getItems() — that is a lazy collection and will fail
     * after multiple saves in the same transaction.
     */
    private PurchaseRequestSummaryDTO mapToSummary(PurchaseRequest pr, List<PurchaseItem> items) {
        PurchaseRequestSummaryDTO dto = new PurchaseRequestSummaryDTO();
        dto.setId(pr.getId());
        dto.setDepartment(pr.getDepartment());
        dto.setReason(pr.getReason());
        dto.setStatus(pr.getStatus());
        dto.setCreatedDate(pr.getCreatedDate());
        dto.setIssuedDate(pr.getIssuedDate());
        dto.setReceivedDate(pr.getReceivedDate());

        if (pr.getToUser() != null) {
            dto.setRequestedByName(pr.getToUser().getFullName());
            dto.setRequestedByEmail(pr.getToUser().getEmail());
        }

        List<PurchaseRequestSummaryDTO.ItemLine> itemLines = items.stream()
                .filter(i -> i.getEquipment() != null)
                .map(i -> new PurchaseRequestSummaryDTO.ItemLine(
                        i.getEquipment().getName(),
                        i.getQuantityRequested()
                ))
                .toList();
        dto.setItems(itemLines);
        return dto;
    }

    // ── HOD view pending ──────────────────────────────────────────────────────

    public List<HodPurchaseRequestDTO> hodViewPending(String hodEmail) {
        User hod = userRepository.findByEmail(hodEmail)
                .orElseThrow(() -> new IllegalArgumentException("Invalid HOD email"));
        if (hod.getRole() != Role.HOD)
            throw new IllegalArgumentException("Only HOD can view purchase requests");

        return purchaseRequestRepository
                .findByHodUserAndStatusOrderByCreatedDateDesc(hod, PurchaseStatus.SUBMITTED_TO_HOD)
                .stream()
                .map(this::mapToHodDto)
                .toList();
    }

    // ── TO → submit to HOD ────────────────────────────────────────────────────

    @Transactional
    public PurchaseRequestSummaryDTO submitToHod(String toEmail, NewPurchaseRequestDTO dto) {
        User toUser = userRepository.findByEmail(toEmail).orElseThrow();
        if (toUser.getRole() != Role.TO)
            throw new IllegalArgumentException("Only TO can submit purchase requests to HOD");
        if (dto.items() == null || dto.items().isEmpty())
            throw new IllegalArgumentException("At least one equipment item is required");

        User hodUser = userRepository
                .findByDepartmentAndRole(toUser.getDepartment(), Role.HOD)
                .orElseThrow(() -> new IllegalArgumentException("No HOD found for department " + toUser.getDepartment()));

        PurchaseRequest pr = new PurchaseRequest();
        pr.setDepartment(toUser.getDepartment());
        pr.setToUser(toUser);
        pr.setHodUser(hodUser);
        pr.setCreatedDate(LocalDate.now());
        pr.setStatus(PurchaseStatus.SUBMITTED_TO_HOD);
        pr.setReason(dto.reason());
        PurchaseRequest saved = purchaseRequestRepository.save(pr);

        for (NewPurchaseItemDTO itemDto : dto.items()) {
            if (itemDto.equipmentId() == null)
                throw new IllegalArgumentException("equipmentId is required");
            if (itemDto.quantityRequested() <= 0)
                throw new IllegalArgumentException("quantityRequested must be > 0");

            Equipment eq = equipmentRepository.findById(itemDto.equipmentId())
                    .orElseThrow(() -> new IllegalArgumentException("Invalid equipment id: " + itemDto.equipmentId()));
            if (eq.getLab() == null || eq.getLab().getDepartment() == null ||
                    !eq.getLab().getDepartment().equalsIgnoreCase(toUser.getDepartment()))
                throw new IllegalArgumentException("Equipment does not belong to TO's department lab");

            PurchaseItem pi = new PurchaseItem();
            pi.setPurchaseRequest(saved);
            pi.setEquipment(eq);
            pi.setQuantityRequested(itemDto.quantityRequested());
            pi.setRemark(itemDto.remark());
            purchaseItemRepository.save(pi);
        }

        notificationService.notifyUser(
                hodUser,
                NotificationType.PURCHASE_SUBMITTED,
                "New purchase request",
                "TO " + toUser.getFullName() + " submitted a purchase request for department " + toUser.getDepartment() + ".",
                null, saved.getId()
        );
        notificationService.notifyUser(
                toUser,
                NotificationType.PURCHASE_SUBMITTED,
                "Purchase request submitted",
                "Your purchase request was submitted to HOD " + hodUser.getFullName() + ".",
                null, saved.getId()
        );

        List<PurchaseItem> savedItems = purchaseItemRepository.findByPurchaseRequestId(saved.getId());
        return new PurchaseRequestSummaryDTO(
                "Request submitted successfully",
                saved.getId(), saved.getDepartment(), saved.getStatus(),
                saved.getReason(), saved.getCreatedDate(),
                toUser.getFullName(), hodUser.getFullName(),
                savedItems.stream()
                        .filter(pi -> pi.getEquipment() != null)
                        .map(pi -> new PurchaseRequestSummaryDTO.ItemLine(pi.getEquipment().getName(), pi.getQuantityRequested()))
                        .toList()
        );
    }

    // ── HOD → approve or reject ───────────────────────────────────────────────

    @Transactional
    public PurchaseRequestSummaryDTO hodDecision(String hodEmail, Long purchaseRequestId,
                                                  boolean approve, String comment) {
        User hodUser = userRepository.findByEmail(hodEmail).orElseThrow();
        if (hodUser.getRole() != Role.HOD)
            throw new IllegalArgumentException("Only HOD can take decision on purchase requests");

        PurchaseRequest pr = purchaseRequestRepository.findById(purchaseRequestId)
                .orElseThrow(() -> new IllegalArgumentException("Purchase request not found"));
        if (pr.getHodUser() != null && !hodUser.getId().equals(pr.getHodUser().getId()))
            throw new IllegalArgumentException("This purchase request does not belong to this HOD");
        if (pr.getStatus() != PurchaseStatus.SUBMITTED_TO_HOD)
            throw new IllegalArgumentException("Purchase request is not in SUBMITTED_TO_HOD state");

        User toUser = pr.getToUser();

        if (approve) {
            pr.setStatus(PurchaseStatus.APPROVED_BY_HOD);
            purchaseRequestRepository.save(pr);

            // Notify all admin users
            List<User> admins = userRepository.findByRole(Role.ADMIN);
            for (User admin : admins) {
                notificationService.notifyUser(
                        admin,
                        NotificationType.PURCHASE_APPROVED_BY_HOD,
                        "Purchase Request Ready for Admin Approval",
                        "HOD " + hodUser.getFullName() + " approved purchase request #" + pr.getId() +
                        " (" + pr.getDepartment() + " dept). Awaiting your final approval.",
                        null, pr.getId()
                );
            }

            // Notify TO
            if (toUser != null) {
                notificationService.notifyUser(
                        toUser,
                        NotificationType.PURCHASE_APPROVED_BY_HOD,
                        "Purchase approved by HOD",
                        "HOD " + hodUser.getFullName() + " approved your purchase request. " +
                        (comment != null ? comment : ""),
                        null, pr.getId()
                );
            }
        } else {
            pr.setStatus(PurchaseStatus.REJECTED_BY_HOD);
            purchaseRequestRepository.save(pr);

            if (toUser != null) {
                notificationService.notifyUser(
                        toUser,
                        NotificationType.PURCHASE_REJECTED_BY_HOD,
                        "Purchase rejected by HOD",
                        "HOD " + hodUser.getFullName() + " rejected your purchase request. " +
                        (comment != null ? comment : ""),
                        null, pr.getId()
                );
            }
        }

        List<PurchaseItem> items = purchaseItemRepository.findByPurchaseRequestId(pr.getId());
        return mapToSummary(pr, items);
    }

    // ── Admin → approve or reject ─────────────────────────────────────────────

    @Transactional
    public PurchaseRequestSummaryDTO adminDecision(String adminEmail,
                                                   Long purchaseRequestId,
                                                   boolean approve,
                                                   String comment,
                                                   LocalDate issuedDate) {
        User adminUser = userRepository.findByEmail(adminEmail).orElseThrow();
        if (adminUser.getRole() != Role.ADMIN)
            throw new IllegalArgumentException("Only ADMIN can approve/reject purchases at admin level");

        PurchaseRequest pr = purchaseRequestRepository.findById(purchaseRequestId)
                .orElseThrow(() -> new IllegalArgumentException("Purchase request not found"));
        if (pr.getStatus() != PurchaseStatus.APPROVED_BY_HOD)
            throw new IllegalArgumentException("Purchase request is not in APPROVED_BY_HOD state");

        User hodUser = pr.getHodUser();

        if (approve) {
            pr.setStatus(PurchaseStatus.ISSUED_BY_ADMIN);
            pr.setIssuedDate(issuedDate != null ? issuedDate : LocalDate.now());
            purchaseRequestRepository.save(pr);

            if (hodUser != null) {
                notificationService.notifyUser(
                        hodUser,
                        NotificationType.PURCHASE_APPROVED_BY_ADMIN,
                        "Purchase issued by Admin",
                        "Admin issued the department purchase request. " + (comment != null ? comment : ""),
                        null, pr.getId()
                );
            }
            if (pr.getToUser() != null) {
                notificationService.notifyUser(
                        pr.getToUser(),
                        NotificationType.PURCHASE_APPROVED_BY_ADMIN,
                        "Purchase issued by Admin",
                        "Admin issued the purchase request. " + (comment != null ? comment : ""),
                        null, pr.getId()
                );
            }
        } else {
            pr.setStatus(PurchaseStatus.REJECTED_BY_ADMIN);
            purchaseRequestRepository.save(pr);

            if (hodUser != null) {
                notificationService.notifyUser(
                        hodUser,
                        NotificationType.PURCHASE_REJECTED_BY_ADMIN,
                        "Purchase rejected by Admin",
                        "Admin rejected the department purchase request. " + (comment != null ? comment : ""),
                        null, pr.getId()
                );
            }
            if (pr.getToUser() != null) {
                notificationService.notifyUser(
                        pr.getToUser(),
                        NotificationType.PURCHASE_REJECTED_BY_ADMIN,
                        "Purchase rejected by Admin",
                        "Admin rejected the purchase request. " + (comment != null ? comment : ""),
                        null, pr.getId()
                );
            }
        }

        List<PurchaseItem> items = purchaseItemRepository.findByPurchaseRequestId(pr.getId());
        return mapToSummary(pr, items);
    }

    // ── TO: view my requests ──────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PurchaseRequestSummaryDTO> toMyPurchaseRequests(String toEmail) {
        User toUser = userRepository.findByEmail(toEmail)
                .orElseThrow(() -> new IllegalArgumentException("Invalid TO email"));
        if (toUser.getRole() != Role.TO)
            throw new IllegalArgumentException("Only TO can view their purchase requests");

        return purchaseRequestRepository.findByToUserOrderByCreatedDateDesc(toUser)
                .stream()
                .map(pr -> mapToSummary(pr, purchaseItemRepository.findByPurchaseRequestId(pr.getId())))
                .toList();
    }

    // ── HOD: view all my requests ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PurchaseRequestSummaryDTO> hodMyPurchaseRequests(String hodEmail) {
        User hod = userRepository.findByEmail(hodEmail)
                .orElseThrow(() -> new IllegalArgumentException("HOD not found"));
        if (hod.getRole() != Role.HOD)
            throw new IllegalArgumentException("Only HOD can view these requests");

        return purchaseRequestRepository.findByHodUserOrderByCreatedDateDesc(hod)
                .stream()
                .map(pr -> mapToSummary(pr, purchaseItemRepository.findByPurchaseRequestId(pr.getId())))
                .toList();
    }

    // ── HOD: confirm items received ───────────────────────────────────────────

    @Transactional
    public PurchaseRequestSummaryDTO hodConfirmReceived(String hodEmail, Long purchaseRequestId) {
        User hodUser = userRepository.findByEmail(hodEmail)
                .orElseThrow(() -> new IllegalArgumentException("Invalid HOD email"));
        if (hodUser.getRole() != Role.HOD)
            throw new IllegalArgumentException("Only HOD can confirm received purchases");

        PurchaseRequest pr = purchaseRequestRepository.findById(purchaseRequestId)
                .orElseThrow(() -> new IllegalArgumentException("Purchase request not found"));
        if (pr.getHodUser() == null || !pr.getHodUser().getId().equals(hodUser.getId()))
            throw new IllegalArgumentException("This purchase request does not belong to this HOD");
        if (pr.getStatus() != PurchaseStatus.ISSUED_BY_ADMIN
                && pr.getStatus() != PurchaseStatus.APPROVED_BY_ADMIN)
            throw new IllegalArgumentException("Purchase request is not in ISSUED_BY_ADMIN state");

        // Load items via repository BEFORE any saves — keeps them in-memory safely
        List<PurchaseItem> items = purchaseItemRepository.findByPurchaseRequestId(pr.getId());

        // Update inventory
        for (PurchaseItem pi : items) {
            Equipment eq = pi.getEquipment();
            if (eq == null) continue;
            int add = pi.getQuantityRequested();
            if (add <= 0) continue;
            eq.setTotalQty(eq.getTotalQty() + add);
            eq.setAvailableQty(eq.getAvailableQty() + add);
            equipmentRepository.save(eq);
        }

        pr.setStatus(PurchaseStatus.RECEIVED_BY_HOD);
        pr.setReceivedDate(LocalDate.now());
        purchaseRequestRepository.save(pr);

        // Build items text for notifications
        String itemsText = items.stream()
                .filter(pi -> pi.getEquipment() != null)
                .map(pi -> pi.getEquipment().getName() + " ×" + pi.getQuantityRequested())
                .reduce((a, b) -> a + ", " + b)
                .orElse("—");

        // Notify all Admin users
        List<User> admins = userRepository.findByRole(Role.ADMIN);
        for (User admin : admins) {
            notificationService.notifyUser(
                    admin,
                    NotificationType.PURCHASE_RECEIVED_BY_HOD,
                    "Purchase Items Confirmed Received by HOD",
                    "HOD " + hodUser.getFullName() + " confirmed receipt of purchase #" + pr.getId() +
                    " (" + pr.getDepartment() + " dept). Items: " + itemsText +
                    ". Inventory updated. Received: " + pr.getReceivedDate() + ".",
                    null, pr.getId()
            );
        }

        // Notify TO
        if (pr.getToUser() != null) {
            notificationService.notifyUser(
                    pr.getToUser(),
                    NotificationType.PURCHASE_RECEIVED_BY_HOD,
                    "Purchase received — Inventory updated",
                    "HOD " + hodUser.getFullName() + " confirmed receipt of purchase #" + pr.getId() +
                    ". Items: " + itemsText + ". Inventory updated.",
                    null, pr.getId()
            );
        }

        // Use the already-loaded items list — NEVER call pr.getItems() after multiple saves
        return mapToSummary(pr, items);
    }
}