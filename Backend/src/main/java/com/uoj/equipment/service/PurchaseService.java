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

    // ─────────────────────────────────────────────────────────────
    // MAPPERS
    // ─────────────────────────────────────────────────────────────

    private HodPurchaseRequestDTO mapToHodDto(PurchaseRequest pr) {
        var itemDtos = pr.getItems().stream()
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

    private PurchaseRequestSummaryDTO mapToSummary(PurchaseRequest pr) {
        PurchaseRequestSummaryDTO dto = new PurchaseRequestSummaryDTO();
        dto.setId(pr.getId());
        dto.setDepartment(pr.getDepartment());
        dto.setReason(pr.getReason());
        dto.setStatus(pr.getStatus());
        dto.setCreatedDate(pr.getCreatedDate());
        dto.setIssuedDate(pr.getIssuedDate());       // ← CRITICAL: issuedDate must be set
        dto.setReceivedDate(pr.getReceivedDate());

        if (pr.getToUser() != null) {
            dto.setRequestedByName(pr.getToUser().getFullName());
            dto.setRequestedByEmail(pr.getToUser().getEmail());
        }

        List<PurchaseRequestSummaryDTO.ItemLine> itemDtos = pr.getItems().stream()
                .map(i -> new PurchaseRequestSummaryDTO.ItemLine(
                        i.getEquipment().getName(),
                        i.getQuantityRequested()
                ))
                .toList();
        dto.setItems(itemDtos);
        return dto;
    }

    // ─────────────────────────────────────────────────────────────
    // HOD VIEW PENDING
    // ─────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────
    // TO → SUBMIT PURCHASE REQUEST TO HOD
    // ─────────────────────────────────────────────────────────────

    @Transactional
    public PurchaseRequestSummaryDTO submitToHod(String toEmail, NewPurchaseRequestDTO dto) {
        User toUser = userRepository.findByEmail(toEmail).orElseThrow();
        if (toUser.getRole() != Role.TO)
            throw new IllegalArgumentException("Only TO can submit purchase requests to HOD");
        if (dto.items() == null || dto.items().isEmpty())
            throw new IllegalArgumentException("At least one equipment item is required");

        User hodUser = userRepository
                .findByDepartmentAndRole(toUser.getDepartment(), Role.HOD)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No HOD found for department " + toUser.getDepartment()));

        PurchaseRequest pr = new PurchaseRequest();
        pr.setDepartment(toUser.getDepartment());
        pr.setToUser(toUser);
        pr.setHodUser(hodUser);
        pr.setCreatedDate(LocalDate.now());
        pr.setStatus(PurchaseStatus.SUBMITTED_TO_HOD);
        pr.setReason(dto.reason());
        PurchaseRequest saved = purchaseRequestRepository.save(pr);

        int totalItems = 0;
        StringBuilder itemsSummary = new StringBuilder();
        for (NewPurchaseItemDTO itemDto : dto.items()) {
            if (itemDto.equipmentId() == null)
                throw new IllegalArgumentException("equipmentId is required");
            if (itemDto.quantityRequested() <= 0)
                throw new IllegalArgumentException("quantityRequested must be > 0");

            Equipment eq = equipmentRepository.findById(itemDto.equipmentId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Invalid equipment id: " + itemDto.equipmentId()));
            if (eq.getLab() == null || eq.getLab().getDepartment() == null ||
                    !eq.getLab().getDepartment().equalsIgnoreCase(toUser.getDepartment()))
                throw new IllegalArgumentException(
                        "Equipment does not belong to TO's department lab");

            PurchaseItem pi = new PurchaseItem();
            pi.setPurchaseRequest(saved);
            pi.setEquipment(eq);
            pi.setQuantityRequested(itemDto.quantityRequested());
            pi.setRemark(itemDto.remark());
            purchaseItemRepository.save(pi);

            totalItems++;
            if (itemsSummary.length() > 0) itemsSummary.append(", ");
            itemsSummary.append(eq.getName()).append(" ×").append(itemDto.quantityRequested());
        }

        String deptName   = toUser.getDepartment();
        String toName     = toUser.getFullName();
        String hodName    = hodUser.getFullName();
        String itemsText  = itemsSummary.toString();
        String reasonText = (dto.reason() != null && !dto.reason().isBlank())
                ? " Reason: \"" + dto.reason() + "\"." : "";

        // ── Notify HOD: new purchase request awaiting approval ─────────────
        notificationService.notifyUser(
                hodUser,
                NotificationType.PURCHASE_SUBMITTED,
                "New Purchase Request Awaiting Your Approval",
                "Technical Officer " + toName + " has submitted a purchase request (#" + saved.getId() +
                ") for the " + deptName + " department. " +
                "Items requested: " + itemsText + "." + reasonText +
                " Please review and approve or reject.",
                null, saved.getId()
        );

        // ── Notify TO: submission confirmed ───────────────────────────────
        notificationService.notifyUser(
                toUser,
                NotificationType.PURCHASE_SUBMITTED,
                "Purchase Request Submitted Successfully",
                "Your purchase request #" + saved.getId() + " has been submitted to HOD " +
                hodName + " for the " + deptName + " department. " +
                "Items: " + itemsText + "." + reasonText +
                " You will be notified once the HOD reviews your request.",
                null, saved.getId()
        );

        List<PurchaseRequestSummaryDTO.ItemLine> itemLines =
                purchaseItemRepository.findByPurchaseRequestId(saved.getId()).stream()
                        .map(pi -> new PurchaseRequestSummaryDTO.ItemLine(
                                pi.getEquipment().getName(), pi.getQuantityRequested()))
                        .toList();

        return new PurchaseRequestSummaryDTO(
                "Request submitted successfully",
                saved.getId(), saved.getDepartment(), saved.getStatus(),
                saved.getReason(), saved.getCreatedDate(),
                toUser.getFullName(), hodUser.getFullName(), itemLines
        );
    }

    // ─────────────────────────────────────────────────────────────
    // HOD → APPROVE OR REJECT PURCHASE REQUEST
    // ─────────────────────────────────────────────────────────────

    @Transactional
    public PurchaseRequestSummaryDTO hodDecision(String hodEmail, Long purchaseRequestId,
                                                  boolean approve, String comment) {
        User hodUser = userRepository.findByEmail(hodEmail).orElseThrow();
        if (hodUser.getRole() != Role.HOD)
            throw new IllegalArgumentException("Only HOD can take decision on purchase requests");

        PurchaseRequest pr = purchaseRequestRepository.findById(purchaseRequestId)
                .orElseThrow(() -> new IllegalArgumentException("Purchase request not found"));
        if (!hodUser.getId().equals(pr.getHodUser().getId()))
            throw new IllegalArgumentException("This purchase request does not belong to this HOD");
        if (pr.getStatus() != PurchaseStatus.SUBMITTED_TO_HOD)
            throw new IllegalArgumentException("Purchase request is not in SUBMITTED_TO_HOD state");

        User toUser  = pr.getToUser();
        String hodName = hodUser.getFullName();
        String deptName = pr.getDepartment();
        String commentText = (comment != null && !comment.isBlank()) ? " Comment: \"" + comment + "\"." : "";

        // Build items summary for notification
        List<PurchaseItem> purchaseItems = purchaseItemRepository.findByPurchaseRequestId(pr.getId());
        String itemsSummary = purchaseItems.stream()
                .map(pi -> pi.getEquipment().getName() + " ×" + pi.getQuantityRequested())
                .reduce((a, b) -> a + ", " + b).orElse("—");

        if (approve) {
            pr.setStatus(PurchaseStatus.APPROVED_BY_HOD);
            purchaseRequestRepository.save(pr);

            // ── Notify TO: HOD approved, forwarded to Admin ────────────────
            notificationService.notifyUser(
                    toUser,
                    NotificationType.PURCHASE_APPROVED_BY_HOD,
                    "Purchase Request Approved by HOD",
                    "HOD " + hodName + " has approved your purchase request #" + pr.getId() +
                    " for the " + deptName + " department. " +
                    "Items: " + itemsSummary + "." + commentText +
                    " The request has been forwarded to the Admin for final approval. " +
                    "You will be notified once the Admin issues the purchase.",
                    null, pr.getId()
            );
        } else {
            pr.setStatus(PurchaseStatus.REJECTED_BY_HOD);
            purchaseRequestRepository.save(pr);

            // ── Notify TO: HOD rejected ────────────────────────────────────
            notificationService.notifyUser(
                    toUser,
                    NotificationType.PURCHASE_REJECTED_BY_HOD,
                    "Purchase Request Rejected by HOD",
                    "HOD " + hodName + " has rejected your purchase request #" + pr.getId() +
                    " for the " + deptName + " department." + commentText +
                    " Items requested: " + itemsSummary + "." +
                    " You may resubmit a revised request if needed.",
                    null, pr.getId()
            );
        }

        List<PurchaseRequestSummaryDTO.ItemLine> itemLines = purchaseItems.stream()
                .map(pi -> new PurchaseRequestSummaryDTO.ItemLine(
                        pi.getEquipment().getName(), pi.getQuantityRequested()))
                .toList();

        return new PurchaseRequestSummaryDTO(
                "", pr.getId(), pr.getDepartment(), pr.getStatus(),
                pr.getReason(), pr.getCreatedDate(),
                toUser.getFullName(), hodUser.getFullName(), itemLines
        );
    }

    // ─────────────────────────────────────────────────────────────
    // ADMIN → APPROVE OR REJECT DEPARTMENT PURCHASE
    // ─────────────────────────────────────────────────────────────

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
        User toUser  = pr.getToUser();
        String deptName    = pr.getDepartment();
        String commentText = (comment != null && !comment.isBlank()) ? " Note: \"" + comment + "\"." : "";

        List<PurchaseItem> purchaseItems = purchaseItemRepository.findByPurchaseRequestId(pr.getId());
        String itemsSummary = purchaseItems.stream()
                .map(pi -> pi.getEquipment().getName() + " ×" + pi.getQuantityRequested())
                .reduce((a, b) -> a + ", " + b).orElse("—");

        if (approve) {
            LocalDate effectiveIssuedDate = issuedDate != null ? issuedDate : LocalDate.now();
            pr.setStatus(PurchaseStatus.ISSUED_BY_ADMIN);
            pr.setIssuedDate(effectiveIssuedDate);
            purchaseRequestRepository.save(pr);

            String issuedDateStr = effectiveIssuedDate.toString();

            // ── Notify HOD: Admin issued purchase, please confirm receipt ──
            notificationService.notifyUser(
                    hodUser,
                    NotificationType.PURCHASE_APPROVED_BY_ADMIN,
                    "Purchase Request Issued — Please Confirm Receipt",
                    "The Admin has issued purchase request #" + pr.getId() +
                    " for the " + deptName + " department. Issued date: " + issuedDateStr + "." +
                    commentText + " Items: " + itemsSummary + "." +
                    " Please confirm receipt in the system once the items arrive to update inventory.",
                    null, pr.getId()
            );

            // ── Notify TO: Admin approved their request ────────────────────
            notificationService.notifyUser(
                    toUser,
                    NotificationType.PURCHASE_APPROVED_BY_ADMIN,
                    "Purchase Request Approved and Issued by Admin",
                    "The Admin has approved and issued purchase request #" + pr.getId() +
                    " for the " + deptName + " department. Issued date: " + issuedDateStr + "." +
                    commentText + " Items: " + itemsSummary + "." +
                    " The HOD will confirm receipt and update inventory once items arrive.",
                    null, pr.getId()
            );

        } else {
            pr.setStatus(PurchaseStatus.REJECTED_BY_ADMIN);
            purchaseRequestRepository.save(pr);

            // ── Notify HOD: Admin rejected ─────────────────────────────────
            notificationService.notifyUser(
                    hodUser,
                    NotificationType.PURCHASE_REJECTED_BY_ADMIN,
                    "Purchase Request Rejected by Admin",
                    "The Admin has rejected purchase request #" + pr.getId() +
                    " for the " + deptName + " department." + commentText +
                    " Items requested: " + itemsSummary + "." +
                    " Please contact the Admin office for further clarification.",
                    null, pr.getId()
            );

            // ── Notify TO: Admin rejected ──────────────────────────────────
            notificationService.notifyUser(
                    toUser,
                    NotificationType.PURCHASE_REJECTED_BY_ADMIN,
                    "Purchase Request Rejected by Admin",
                    "The Admin has rejected purchase request #" + pr.getId() +
                    " for the " + deptName + " department." + commentText +
                    " Items requested: " + itemsSummary + "." +
                    " Please speak with the HOD if you wish to resubmit.",
                    null, pr.getId()
            );
        }

        List<PurchaseRequestSummaryDTO.ItemLine> itemLines = purchaseItems.stream()
                .map(pi -> new PurchaseRequestSummaryDTO.ItemLine(
                        pi.getEquipment().getName(), pi.getQuantityRequested()))
                .toList();

        return new PurchaseRequestSummaryDTO(
                "", pr.getId(), pr.getDepartment(), pr.getStatus(),
                pr.getReason(), pr.getCreatedDate(),
                pr.getToUser().getFullName(), hodUser.getFullName(), itemLines
        );
    }

    // ─────────────────────────────────────────────────────────────
    // TO — VIEW MY PURCHASE REQUESTS
    // ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PurchaseRequestSummaryDTO> toMyPurchaseRequests(String toEmail) {
        User toUser = userRepository.findByEmail(toEmail)
                .orElseThrow(() -> new IllegalArgumentException("Invalid TO email"));
        if (toUser.getRole() != Role.TO)
            throw new IllegalArgumentException("Only TO can view their purchase requests");

        return purchaseRequestRepository.findByToUserOrderByCreatedDateDesc(toUser)
                .stream().map(this::mapToSummary).toList();
    }

    // ─────────────────────────────────────────────────────────────
    // HOD — VIEW ALL MY PURCHASE REQUESTS
    // ─────────────────────────────────────────────────────────────

    public List<PurchaseRequestSummaryDTO> hodMyPurchaseRequests(String hodEmail) {
        User hod = userRepository.findByEmail(hodEmail)
                .orElseThrow(() -> new IllegalArgumentException("HOD not found"));
        if (hod.getRole() != Role.HOD)
            throw new IllegalArgumentException("Only HOD can view these requests");

        return purchaseRequestRepository.findByHodUserOrderByCreatedDateDesc(hod)
                .stream().map(this::mapToSummary).toList();
    }

    // ─────────────────────────────────────────────────────────────
    // HOD — CONFIRM ITEMS RECEIVED (updates inventory)
    // ─────────────────────────────────────────────────────────────

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

        // ── Update inventory ───────────────────────────────────────────────
        List<PurchaseItem> items = purchaseItemRepository.findByPurchaseRequestId(pr.getId());
        StringBuilder itemsSummary = new StringBuilder();
        for (PurchaseItem pi : items) {
            Equipment eq = pi.getEquipment();
            if (eq == null || pi.getQuantityRequested() <= 0) continue;
            eq.setTotalQty(eq.getTotalQty() + pi.getQuantityRequested());
            eq.setAvailableQty(eq.getAvailableQty() + pi.getQuantityRequested());
            equipmentRepository.save(eq);
            if (itemsSummary.length() > 0) itemsSummary.append(", ");
            itemsSummary.append(eq.getName()).append(" ×").append(pi.getQuantityRequested());
        }

        pr.setStatus(PurchaseStatus.RECEIVED_BY_HOD);
        pr.setReceivedDate(LocalDate.now());
        purchaseRequestRepository.save(pr);

        String hodName   = hodUser.getFullName();
        String deptName  = pr.getDepartment();
        String itemsText = itemsSummary.length() > 0 ? itemsSummary.toString() : "—";

        // ── Notify TO: items received, inventory updated ───────────────────
        if (pr.getToUser() != null) {
            notificationService.notifyUser(
                    pr.getToUser(),
                    NotificationType.PURCHASE_RECEIVED_BY_HOD,
                    "Purchase Items Received — Inventory Updated",
                    "HOD " + hodName + " has confirmed receipt of the items for purchase request #" +
                    pr.getId() + " (" + deptName + " department). " +
                    "Items received: " + itemsText + ". " +
                    "The lab inventory has been updated accordingly. " +
                    "Received date: " + pr.getReceivedDate() + ".",
                    null, pr.getId()
            );
        }

        return mapToSummary(pr);
    }
}