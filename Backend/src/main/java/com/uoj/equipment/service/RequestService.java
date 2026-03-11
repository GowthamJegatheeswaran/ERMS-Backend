package com.uoj.equipment.service;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.uoj.equipment.dto.IssuedItemDTO;
import com.uoj.equipment.dto.NewRequestDTO;
import com.uoj.equipment.dto.RequestSummaryDTO;
import com.uoj.equipment.dto.RequestSummaryItemDTO;
import com.uoj.equipment.dto.StudentAcceptanceDTO;
import com.uoj.equipment.dto.StudentMyRequestDTO;
import com.uoj.equipment.dto.StudentMyRequestItemDTO;
import com.uoj.equipment.dto.StudentReturnDTO;
import com.uoj.equipment.dto.ToApprovedRequestDTO;
import com.uoj.equipment.dto.ToApprovedRequestItemDTO;
import com.uoj.equipment.dto.ToIssueResponseDTO;
import com.uoj.equipment.dto.ToVerifyReturnResponseDTO;
import com.uoj.equipment.dto.VerifiedReturnItemDTO;
import com.uoj.equipment.entity.Equipment;
import com.uoj.equipment.entity.EquipmentRequest;
import com.uoj.equipment.entity.Lab;
import com.uoj.equipment.entity.RequestItem;
import com.uoj.equipment.entity.User;
import com.uoj.equipment.enums.ItemType;
import com.uoj.equipment.enums.NotificationType;
import com.uoj.equipment.enums.RequestItemStatus;
import com.uoj.equipment.enums.RequestStatus;
import com.uoj.equipment.enums.Role;
import com.uoj.equipment.repository.EquipmentRepository;
import com.uoj.equipment.repository.EquipmentRequestRepository;
import com.uoj.equipment.repository.LabRepository;
import com.uoj.equipment.repository.RequestItemRepository;
import com.uoj.equipment.repository.UserRepository;

@Service
public class RequestService {

    private final UserRepository userRepository;
    private final LabRepository labRepository;
    private final EquipmentRepository equipmentRepository;
    private final EquipmentRequestRepository equipmentRequestRepository;
    private final RequestItemRepository requestItemRepository;
    private final PriorityService priorityService;
    private final NotificationService notificationService;

    public RequestService(UserRepository userRepository,
                          LabRepository labRepository,
                          EquipmentRepository equipmentRepository,
                          EquipmentRequestRepository equipmentRequestRepository,
                          RequestItemRepository requestItemRepository,
                          PriorityService priorityService,
                          NotificationService notificationService) {
        this.userRepository = userRepository;
        this.labRepository = labRepository;
        this.equipmentRepository = equipmentRepository;
        this.equipmentRequestRepository = equipmentRequestRepository;
        this.requestItemRepository = requestItemRepository;
        this.priorityService = priorityService;
        this.notificationService = notificationService;
    }

    // ─────────────────────────────────────────────────────────────
    // LECTURER QUEUE  —  sorted by priority score descending
    // ─────────────────────────────────────────────────────────────

    public List<RequestSummaryDTO> lecturerQueueDTO(String lecturerEmail) {
        User lecturer = userRepository.findByEmail(lecturerEmail).orElseThrow();
        if (lecturer.getRole() != Role.LECTURER && lecturer.getRole() != Role.HOD)
            throw new IllegalArgumentException("Only lecturer or HOD");

        return equipmentRequestRepository
                .findByLecturerIdAndStatusOrderByIdDesc(lecturer.getId(), RequestStatus.PENDING_LECTURER_APPROVAL)
                .stream()
                .sorted((a, b) -> Integer.compare(b.getPriorityScore(), a.getPriorityScore()))
                .map(this::mapToRequestSummaryDTO)
                .toList();
    }

    @Transactional
    public RequestSummaryDTO lecturerApproveDTO(String lecturerEmail, Long requestId) {
        EquipmentRequest req = lecturerApprove(lecturerEmail, requestId);
        return mapToRequestSummaryDTO(req);
    }

    public RequestSummaryDTO lecturerApproveItemDTO(String lecturerEmail, Long requestItemId) {
        EquipmentRequest req = lecturerApproveItem(lecturerEmail, requestItemId);
        return mapToRequestSummaryDTO(req);
    }

    @Transactional
    public RequestSummaryDTO lecturerRejectDTO(String lecturerEmail, Long requestId, String reason) {
        EquipmentRequest req = lecturerReject(lecturerEmail, requestId, reason);
        return mapToRequestSummaryDTO(req);
    }

    public RequestSummaryDTO lecturerRejectItemDTO(String lecturerEmail, Long requestItemId, String reason) {
        EquipmentRequest req = lecturerRejectItem(lecturerEmail, requestItemId, reason);
        return mapToRequestSummaryDTO(req);
    }

    @Transactional
    public RequestSummaryDTO createRequestAndReturnDTO(String requesterEmail, NewRequestDTO dto) {
        EquipmentRequest saved = createRequest(requesterEmail, dto);
        return mapToRequestSummaryDTO(saved);
    }

    // ─────────────────────────────────────────────────────────────
    // STUDENT MY REQUESTS
    // ─────────────────────────────────────────────────────────────

    public List<StudentMyRequestDTO> myRequestsStudentView(String requesterEmail) {
        User requester = userRepository.findByEmail(requesterEmail).orElseThrow();
        return equipmentRequestRepository.findByRequesterIdOrderByIdDesc(requester.getId())
                .stream()
                .map(this::mapToStudentMyRequestDTO)
                .toList();
    }

    // ─────────────────────────────────────────────────────────────
    // TO APPROVED REQUESTS  —  sorted by priority score descending
    // ─────────────────────────────────────────────────────────────

    public List<ToApprovedRequestDTO> toApprovedRequestsForLabView(String toEmail, Long labId) {
        User to = userRepository.findByEmail(toEmail).orElseThrow();
        if (to.getRole() != Role.TO) throw new IllegalArgumentException("Only TO");

        Lab lab = labRepository.findById(labId)
                .orElseThrow(() -> new IllegalArgumentException("Lab not found"));

        ensureToAllowedForLab(to, lab);

        return equipmentRequestRepository
                .findByLabIdAndStatusOrderByIdDesc(labId, RequestStatus.APPROVED_BY_LECTURER)
                .stream()
                .sorted((a, b) -> Integer.compare(b.getPriorityScore(), a.getPriorityScore()))
                .map(this::mapToApprovedRequestDTO)
                .toList();
    }

    // ─────────────────────────────────────────────────────────────
    // CREATE REQUEST
    // ─────────────────────────────────────────────────────────────

    @Transactional
    public EquipmentRequest createRequest(String requesterEmail, NewRequestDTO dto) {
        User requester = userRepository.findByEmail(requesterEmail).orElseThrow();

        if (requester.getRole() != Role.STUDENT &&
            requester.getRole() != Role.STAFF &&
            requester.getRole() != Role.LECTURER &&
            requester.getRole() != Role.HOD)
            throw new IllegalArgumentException("Not allowed to create request");

        if (dto.getLabId() == null) throw new IllegalArgumentException("labId required");
        if (dto.getItems() == null || dto.getItems().isEmpty())
            throw new IllegalArgumentException("At least 1 item required");

        Lab lab = labRepository.findById(dto.getLabId())
                .orElseThrow(() -> new IllegalArgumentException("Lab not found"));

        User lecturer;
        if (requester.getRole() == Role.LECTURER || requester.getRole() == Role.HOD) {
            lecturer = requester;
        } else {
            if (dto.getLecturerId() == null) throw new IllegalArgumentException("lecturerId required");
            lecturer = userRepository.findById(dto.getLecturerId())
                    .orElseThrow(() -> new IllegalArgumentException("Lecturer not found"));
            if (lecturer.getRole() != Role.LECTURER && lecturer.getRole() != Role.HOD)
                throw new IllegalArgumentException("Invalid lecturer");
            if (!com.uoj.equipment.util.DepartmentUtil.equalsNormalized(
                    lecturer.getDepartment(), lab.getDepartment()))
                throw new IllegalArgumentException("Lecturer department mismatch");
        }

        int priorityScore = priorityService.calculate(dto.getPurpose(), dto.getFromDate(), dto.getToDate(), false);

        EquipmentRequest req = new EquipmentRequest();
        req.setRequester(requester);
        req.setLecturer(lecturer);
        req.setLab(lab);
        req.setPurpose(dto.getPurpose());
        req.setFromDate(dto.getFromDate());
        req.setToDate(dto.getToDate());
        req.setFromTime(dto.getFromTime());
        req.setToTime(dto.getToTime());
        req.setLetterAttachmentPath(dto.getLetterAttachmentPath());
        req.setPriorityScore(priorityScore);

        boolean selfApproved = requester.getRole() == Role.LECTURER || requester.getRole() == Role.HOD;
        req.setStatus(selfApproved ? RequestStatus.APPROVED_BY_LECTURER : RequestStatus.PENDING_LECTURER_APPROVAL);

        EquipmentRequest saved = equipmentRequestRepository.save(req);

        for (NewRequestDTO.ItemLine line : dto.getItems()) {
            if (line.getEquipmentId() == null) throw new IllegalArgumentException("equipmentId required");
            if (line.getQuantity() <= 0) throw new IllegalArgumentException("Invalid quantity");

            Equipment eq = equipmentRepository.findById(line.getEquipmentId())
                    .orElseThrow(() -> new IllegalArgumentException("Equipment not found"));
            if (!eq.isActive()) throw new IllegalArgumentException("Equipment inactive: " + eq.getId());
            if (eq.getLab() == null || !eq.getLab().getId().equals(lab.getId()))
                throw new IllegalArgumentException("Equipment not in selected lab: " + eq.getId());

            RequestItem item = new RequestItem();
            item.setRequest(saved);
            item.setEquipment(eq);
            item.setQuantity(line.getQuantity());
            item.setIssuedQty(0);
            item.setReturned(false);
            item.setDamaged(false);
            item.setStatus(selfApproved
                    ? RequestItemStatus.APPROVED_BY_LECTURER
                    : RequestItemStatus.PENDING_LECTURER_APPROVAL);
            requestItemRepository.save(item);
        }

        // ── Notifications ──────────────────────────────────────────────────
        String labName = lab.getName();
        String fromDateStr = dto.getFromDate() != null ? dto.getFromDate().toString() : "—";
        String purposeLabel = dto.getPurpose() != null ? dto.getPurpose().name() : "—";
        int itemCount = dto.getItems().size();

        if (!selfApproved) {
            // Notify student/staff: submission confirmed
            notificationService.notifyUser(
                    requester,
                    NotificationType.REQUEST_SUBMITTED,
                    "Equipment Request Submitted Successfully",
                    "Your equipment request #" + saved.getId() + " for lab \"" + labName + "\" has been submitted. " +
                    "Purpose: " + purposeLabel + " | From: " + fromDateStr + " | Items: " + itemCount + ". " +
                    "Awaiting approval from Lecturer " + lecturer.getFullName() + ".",
                    saved.getId(), null
            );

            // Notify lecturer: new request needs approval
            notificationService.notifyUser(
                    lecturer,
                    NotificationType.REQUEST_SUBMITTED,
                    "New Equipment Request Pending Your Approval",
                    requester.getFullName() + " (" +
                    (requester.getRegNo() != null ? requester.getRegNo() : requester.getEmail()) + ") " +
                    "has submitted an equipment request #" + saved.getId() + " for lab \"" + labName + "\". " +
                    "Purpose: " + purposeLabel + " | From: " + fromDateStr + " | " + itemCount + " item(s). " +
                    "Priority Score: " + priorityScore + "/100. Please review and approve or reject.",
                    saved.getId(), null
            );
        } else {
            // Lecturer/HOD self-request — auto-approved, now notify TO directly
            notificationService.notifyUser(
                    requester,
                    NotificationType.REQUEST_SUBMITTED,
                    "Your Equipment Request Has Been Created",
                    "Your equipment request #" + saved.getId() + " for lab \"" + labName + "\" has been created " +
                    "and auto-approved. Purpose: " + purposeLabel + " | From: " + fromDateStr + " | Items: " + itemCount + ". " +
                    "The Technical Officer has been notified to prepare the equipment.",
                    saved.getId(), null
            );

            // Notify TO directly since request is already APPROVED_BY_LECTURER
            User to = lab.getTechnicalOfficer();
            if (to != null) {
                notificationService.notifyUser(
                        to,
                        NotificationType.REQUEST_APPROVED,
                        "New Equipment Request Ready for Issuance",
                        lecturer.getFullName() + " (" + requester.getRole().name() + ") has submitted a self-approved " +
                        "equipment request #" + saved.getId() + " for lab \"" + labName + "\". " +
                        "Purpose: " + purposeLabel + " | From: " + fromDateStr + " | " + itemCount + " item(s). " +
                        "Priority Score: " + priorityScore + "/100. Please prepare and issue the equipment.",
                        saved.getId(), null
                );
            }
        }

        return saved;
    }

    // ─────────────────────────────────────────────────────────────
    // LECTURER APPROVE / REJECT
    // ─────────────────────────────────────────────────────────────

    @Transactional
    public EquipmentRequest lecturerApprove(String lecturerEmail, Long requestId) {
        User lecturer = userRepository.findByEmail(lecturerEmail).orElseThrow();
        if (lecturer.getRole() != Role.LECTURER && lecturer.getRole() != Role.HOD)
            throw new IllegalArgumentException("Only lecturer or HOD");

        EquipmentRequest req = equipmentRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found"));

        if (req.getLecturer() == null || !req.getLecturer().getId().equals(lecturer.getId()))
            throw new IllegalArgumentException("Not your request");

        if (req.getStatus() != RequestStatus.PENDING_LECTURER_APPROVAL)
            throw new IllegalArgumentException("Not in approval state");

        List<RequestItem> items = requestItemRepository.findByRequestId(req.getId());
        for (RequestItem it : items) {
            if (it.getStatus() == RequestItemStatus.PENDING_LECTURER_APPROVAL) {
                it.setStatus(RequestItemStatus.APPROVED_BY_LECTURER);
                requestItemRepository.save(it);
            }
        }

        recomputeAndPersistRequestStatus(req);

        String labName = req.getLab().getName();
        String fromDate = req.getFromDate() != null ? req.getFromDate().toString() : "—";
        String purposeLabel = req.getPurpose() != null ? req.getPurpose().name() : "—";

        // 1. Notify requester: approved
        notificationService.notifyUser(
                req.getRequester(),
                NotificationType.REQUEST_APPROVED,
                "Your Equipment Request Has Been Approved",
                "Great news! Lecturer " + lecturer.getFullName() + " has approved your equipment request #" +
                req.getId() + " for lab \"" + labName + "\". " +
                "Purpose: " + purposeLabel + " | From: " + fromDate + ". " +
                "The Technical Officer will now prepare your equipment. You will receive a notification when it is ready for collection.",
                req.getId(), null
        );

        // 2. Notify TO: request ready for issuance ← THIS WAS MISSING
        User to = req.getLab().getTechnicalOfficer();
        if (to != null) {
            notificationService.notifyUser(
                    to,
                    NotificationType.REQUEST_APPROVED,
                    "New Approved Request Ready for Issuance",
                    "Lecturer " + lecturer.getFullName() + " has approved equipment request #" + req.getId() +
                    " from " + req.getRequester().getFullName() +
                    (req.getRequester().getRegNo() != null ? " (" + req.getRequester().getRegNo() + ")" : "") +
                    " for lab \"" + labName + "\". " +
                    "Purpose: " + purposeLabel + " | From: " + fromDate + " | " + items.size() + " item(s). " +
                    "Priority Score: " + req.getPriorityScore() + "/100. " +
                    "Please issue the equipment at your earliest convenience.",
                    req.getId(), null
            );
        }

        return req;
    }

    @Transactional
    public EquipmentRequest lecturerReject(String lecturerEmail, Long requestId, String reason) {
        User lecturer = userRepository.findByEmail(lecturerEmail).orElseThrow();
        if (lecturer.getRole() != Role.LECTURER && lecturer.getRole() != Role.HOD)
            throw new IllegalArgumentException("Only lecturer or HOD");

        EquipmentRequest req = equipmentRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found"));

        if (req.getLecturer() == null || !req.getLecturer().getId().equals(lecturer.getId()))
            throw new IllegalArgumentException("Not your request");

        if (req.getStatus() != RequestStatus.PENDING_LECTURER_APPROVAL)
            throw new IllegalArgumentException("Not in approval state");

        List<RequestItem> items = requestItemRepository.findByRequestId(req.getId());
        for (RequestItem it : items) {
            if (it.getStatus() == RequestItemStatus.PENDING_LECTURER_APPROVAL) {
                it.setStatus(RequestItemStatus.REJECTED_BY_LECTURER);
                requestItemRepository.save(it);
            }
        }

        recomputeAndPersistRequestStatus(req);

        String reasonText = (reason != null && !reason.isBlank()) ? " Reason: \"" + reason + "\"." : "";
        notificationService.notifyUser(
                req.getRequester(),
                NotificationType.REQUEST_REJECTED,
                "Your Equipment Request Has Been Rejected",
                "Your equipment request #" + req.getId() + " for lab \"" + req.getLab().getName() +
                "\" was rejected by Lecturer " + lecturer.getFullName() + "." + reasonText +
                " If you believe this is an error or would like to resubmit, please speak with your lecturer.",
                req.getId(), null
        );

        return req;
    }

    @Transactional
    public EquipmentRequest lecturerApproveItem(String lecturerEmail, Long requestItemId) {
        User lecturer = userRepository.findByEmail(lecturerEmail).orElseThrow();
        if (lecturer.getRole() != Role.LECTURER && lecturer.getRole() != Role.HOD)
            throw new IllegalArgumentException("Only lecturer or HOD");

        RequestItem item = requestItemRepository.findById(requestItemId)
                .orElseThrow(() -> new IllegalArgumentException("Request item not found"));
        EquipmentRequest req = item.getRequest();

        if (req.getLecturer() == null || !req.getLecturer().getId().equals(lecturer.getId()))
            throw new IllegalArgumentException("Not your request");

        if (item.getStatus() != RequestItemStatus.PENDING_LECTURER_APPROVAL)
            throw new IllegalArgumentException("Item not pending approval");

        item.setStatus(RequestItemStatus.APPROVED_BY_LECTURER);
        requestItemRepository.save(item);

        recomputeAndPersistRequestStatus(req);

        String labName = req.getLab().getName();
        String equipName = item.getEquipment().getName();

        // 1. Notify requester
        notificationService.notifyUser(
                req.getRequester(),
                NotificationType.REQUEST_APPROVED,
                "Equipment Item Approved",
                "Lecturer " + lecturer.getFullName() + " has approved \"" + equipName + "\" (qty: " + item.getQuantity() + ") " +
                "in your request #" + req.getId() + " for lab \"" + labName + "\". " +
                "The Technical Officer will be notified to prepare this item.",
                req.getId(), null
        );

        // 2. Notify TO ← MISSING BEFORE
        User to = req.getLab().getTechnicalOfficer();
        if (to != null) {
            notificationService.notifyUser(
                    to,
                    NotificationType.REQUEST_APPROVED,
                    "Equipment Item Approved — Action Required",
                    "Lecturer " + lecturer.getFullName() + " has approved item \"" + equipName + "\" (qty: " +
                    item.getQuantity() + ") in request #" + req.getId() + " from " +
                    req.getRequester().getFullName() + " for lab \"" + labName + "\". " +
                    "Please issue this item when ready.",
                    req.getId(), null
            );
        }

        return req;
    }

    @Transactional
    public EquipmentRequest lecturerRejectItem(String lecturerEmail, Long requestItemId, String reason) {
        User lecturer = userRepository.findByEmail(lecturerEmail).orElseThrow();
        if (lecturer.getRole() != Role.LECTURER && lecturer.getRole() != Role.HOD)
            throw new IllegalArgumentException("Only lecturer or HOD");

        RequestItem item = requestItemRepository.findById(requestItemId)
                .orElseThrow(() -> new IllegalArgumentException("Request item not found"));
        EquipmentRequest req = item.getRequest();

        if (req.getLecturer() == null || !req.getLecturer().getId().equals(lecturer.getId()))
            throw new IllegalArgumentException("Not your request");

        if (item.getStatus() != RequestItemStatus.PENDING_LECTURER_APPROVAL)
            throw new IllegalArgumentException("Item not pending approval");

        item.setStatus(RequestItemStatus.REJECTED_BY_LECTURER);
        requestItemRepository.save(item);

        recomputeAndPersistRequestStatus(req);

        String reasonText = (reason != null && !reason.isBlank()) ? " Reason: \"" + reason + "\"." : "";
        notificationService.notifyUser(
                req.getRequester(),
                NotificationType.REQUEST_REJECTED,
                "Equipment Item Rejected",
                "Lecturer " + lecturer.getFullName() + " has rejected \"" + item.getEquipment().getName() +
                "\" in your request #" + req.getId() + "." + reasonText,
                req.getId(), null
        );

        return req;
    }

    // ─────────────────────────────────────────────────────────────
    // TO — ISSUE
    // ─────────────────────────────────────────────────────────────

    @Transactional
    public EquipmentRequest toIssue(String toEmail, Long requestId) {
        User to = userRepository.findByEmail(toEmail).orElseThrow();
        if (to.getRole() != Role.TO) throw new IllegalArgumentException("Only TO");

        EquipmentRequest req = equipmentRequestRepository.findById(requestId).orElseThrow();
        ensureToAllowedForLab(to, req.getLab());

        if (req.getStatus() != RequestStatus.APPROVED_BY_LECTURER)
            throw new IllegalArgumentException("Request not ready for issue");

        List<RequestItem> items = requestItemRepository.findByRequestId(req.getId());
        if (items.isEmpty()) throw new IllegalArgumentException("No request items");

        for (RequestItem it : items) {
            Equipment eq = it.getEquipment();
            if (eq.getAvailableQty() < it.getQuantity())
                throw new IllegalArgumentException("Not enough stock for: " + eq.getName());
        }

        for (RequestItem it : items) {
            Equipment eq = it.getEquipment();
            eq.setAvailableQty(eq.getAvailableQty() - it.getQuantity());
            equipmentRepository.save(eq);
            it.setIssuedQty(it.getQuantity());
            it.setStatus(RequestItemStatus.ISSUED_PENDING_REQUESTER_ACCEPT);
            requestItemRepository.save(it);
        }

        req.setStatus(RequestStatus.ISSUED_PENDING_STUDENT_ACCEPT);
        equipmentRequestRepository.save(req);

        notificationService.notifyUser(
                req.getRequester(),
                NotificationType.ISSUE_READY,
                "Your Equipment is Ready for Collection",
                "Technical Officer " + to.getFullName() + " has issued the equipment for your request #" +
                req.getId() + " at lab \"" + req.getLab().getName() + "\". " +
                "Please visit the lab, collect your equipment, and confirm receipt in the system.",
                req.getId(), null
        );

        return req;
    }

    @Transactional
    public EquipmentRequest toIssueItem(String toEmail, Long requestItemId) {
        User to = userRepository.findByEmail(toEmail).orElseThrow();
        if (to.getRole() != Role.TO) throw new IllegalArgumentException("Only TO");

        RequestItem it = requestItemRepository.findById(requestItemId)
                .orElseThrow(() -> new IllegalArgumentException("Request item not found"));
        EquipmentRequest req = it.getRequest();

        ensureToAllowedForLab(to, req.getLab());

        if (it.getStatus() != RequestItemStatus.APPROVED_BY_LECTURER
                && it.getStatus() != RequestItemStatus.WAITING_TO_ISSUE)
            throw new IllegalArgumentException("Item not ready for issue");

        Equipment eq = it.getEquipment();
        if (eq.getAvailableQty() < it.getQuantity())
            throw new IllegalArgumentException("Not enough stock for: " + eq.getName());

        eq.setAvailableQty(eq.getAvailableQty() - it.getQuantity());
        equipmentRepository.save(eq);

        it.setIssuedQty(it.getQuantity());
        it.setStatus(RequestItemStatus.ISSUED_PENDING_REQUESTER_ACCEPT);
        it.setToWaitReason(null);
        requestItemRepository.save(it);

        recomputeAndPersistRequestStatus(req);

        notificationService.notifyUser(
                req.getRequester(),
                NotificationType.ISSUE_READY,
                "Equipment Item Ready for Collection",
                "Technical Officer " + to.getFullName() + " has issued \"" + eq.getName() + "\" (qty: " +
                it.getIssuedQty() + ") from your request #" + req.getId() + " at lab \"" +
                req.getLab().getName() + "\". Please collect and confirm receipt in the system.",
                req.getId(), null
        );

        return req;
    }

    @Transactional
    public EquipmentRequest toWaitItem(String toEmail, Long requestItemId, String reason) {
        User to = userRepository.findByEmail(toEmail).orElseThrow();
        if (to.getRole() != Role.TO) throw new IllegalArgumentException("Only TO");

        RequestItem it = requestItemRepository.findById(requestItemId)
                .orElseThrow(() -> new IllegalArgumentException("Request item not found"));
        EquipmentRequest req = it.getRequest();

        ensureToAllowedForLab(to, req.getLab());

        if (it.getStatus() != RequestItemStatus.APPROVED_BY_LECTURER)
            throw new IllegalArgumentException("Item not ready for TO action");

        it.setStatus(RequestItemStatus.WAITING_TO_ISSUE);
        it.setToWaitReason(reason == null ? null : reason.trim());
        requestItemRepository.save(it);

        recomputeAndPersistRequestStatus(req);

        String reasonText = (it.getToWaitReason() != null && !it.getToWaitReason().isBlank())
                ? " Reason: \"" + it.getToWaitReason() + "\"."
                : " The Technical Officer will notify you when it becomes available.";
        notificationService.notifyUser(
                req.getRequester(),
                NotificationType.TO_WAIT,
                "Equipment Temporarily Unavailable",
                "Technical Officer " + to.getFullName() + " has placed \"" + it.getEquipment().getName() +
                "\" in your request #" + req.getId() + " on hold." + reasonText +
                " Other approved items in your request are unaffected.",
                req.getId(), null
        );

        return req;
    }

    // ─────────────────────────────────────────────────────────────
    // STUDENT ACCEPT ISSUE
    // ─────────────────────────────────────────────────────────────

    @Transactional
    public EquipmentRequest studentAcceptIssue(String requesterEmail, Long requestId) {
        User requester = userRepository.findByEmail(requesterEmail).orElseThrow();
        if (requester.getRole() != Role.STUDENT &&
            requester.getRole() != Role.STAFF &&
            requester.getRole() != Role.LECTURER)
            throw new IllegalArgumentException("Only requester can accept");

        EquipmentRequest req = equipmentRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found"));

        if (!req.getRequester().getId().equals(requester.getId()))
            throw new IllegalArgumentException("Not your request");

        if (req.getStatus() != RequestStatus.ISSUED_PENDING_STUDENT_ACCEPT)
            throw new IllegalArgumentException("Not waiting for acceptance");

        req.setStatus(RequestStatus.ISSUED_CONFIRMED);
        equipmentRequestRepository.save(req);

        // Notify TO: requester confirmed receipt
        User to = req.getLab().getTechnicalOfficer();
        if (to != null) {
            notificationService.notifyUser(
                    to,
                    NotificationType.ISSUE_ACCEPTED,
                    "Equipment Receipt Confirmed",
                    requester.getFullName() +
                    (requester.getRegNo() != null ? " (" + requester.getRegNo() + ")" : "") +
                    " has confirmed receipt of all equipment for request #" + req.getId() +
                    " at lab \"" + req.getLab().getName() + "\". The transaction is now active.",
                    req.getId(), null
            );
        }

        return req;
    }

    @Transactional
    public EquipmentRequest studentAcceptIssueItem(String requesterEmail, Long requestItemId) {
        User requester = userRepository.findByEmail(requesterEmail).orElseThrow();
        if (requester.getRole() != Role.STUDENT &&
            requester.getRole() != Role.STAFF &&
            requester.getRole() != Role.LECTURER)
            throw new IllegalArgumentException("Only requester can accept");

        RequestItem it = requestItemRepository.findById(requestItemId)
                .orElseThrow(() -> new IllegalArgumentException("Request item not found"));
        EquipmentRequest req = it.getRequest();

        if (!req.getRequester().getId().equals(requester.getId()))
            throw new IllegalArgumentException("Not your request");

        if (it.getStatus() != RequestItemStatus.ISSUED_PENDING_REQUESTER_ACCEPT)
            throw new IllegalArgumentException("Item not waiting for acceptance");

        it.setStatus(RequestItemStatus.ISSUED_CONFIRMED);
        requestItemRepository.save(it);

        recomputeAndPersistRequestStatus(req);

        // Notify TO
        User to = req.getLab().getTechnicalOfficer();
        if (to != null) {
            notificationService.notifyUser(
                    to,
                    NotificationType.ISSUE_ACCEPTED,
                    "Equipment Item Receipt Confirmed",
                    requester.getFullName() +
                    (requester.getRegNo() != null ? " (" + requester.getRegNo() + ")" : "") +
                    " has confirmed receipt of \"" + it.getEquipment().getName() +
                    "\" (qty: " + it.getIssuedQty() + ") from request #" + req.getId() + ".",
                    req.getId(), null
            );
        }

        return req;
    }

    // ─────────────────────────────────────────────────────────────
    // STUDENT SUBMIT RETURN
    // ─────────────────────────────────────────────────────────────

    @Transactional
    public EquipmentRequest submitReturn(String requesterEmail, Long requestId) {
        User requester = userRepository.findByEmail(requesterEmail).orElseThrow();

        EquipmentRequest req = equipmentRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found"));

        if (!req.getRequester().getId().equals(requester.getId()))
            throw new IllegalArgumentException("Not your request");

        if (req.getStatus() != RequestStatus.ISSUED_CONFIRMED)
            throw new IllegalArgumentException("Not in issued state");

        List<RequestItem> items = requestItemRepository.findByRequestId(req.getId());
        boolean hasReturnable = items.stream()
                .anyMatch(i -> i.getEquipment().getItemType() == ItemType.RETURNABLE);

        if (!hasReturnable) throw new IllegalArgumentException("Return not required for NON_RETURNABLE items");

        req.setStatus(RequestStatus.RETURNED_PENDING_TO_VERIFY);
        equipmentRequestRepository.save(req);

        // Notify TO: return submitted ← WRONG TARGET BEFORE (was notifying lecturer)
        User to = req.getLab().getTechnicalOfficer();
        if (to != null) {
            notificationService.notifyUser(
                    to,
                    NotificationType.RETURN_SUBMITTED,
                    "Equipment Return Submitted — Inspection Required",
                    requester.getFullName() +
                    (requester.getRegNo() != null ? " (" + requester.getRegNo() + ")" : "") +
                    " has submitted a return for request #" + req.getId() +
                    " at lab \"" + req.getLab().getName() + "\". " +
                    "Please inspect the returned equipment and verify or report damage.",
                    req.getId(), null
            );
        }

        return req;
    }

    @Transactional
    public EquipmentRequest submitReturnItem(String requesterEmail, Long requestItemId) {
        User requester = userRepository.findByEmail(requesterEmail).orElseThrow();

        RequestItem it = requestItemRepository.findById(requestItemId)
                .orElseThrow(() -> new IllegalArgumentException("Request item not found"));
        EquipmentRequest req = it.getRequest();

        if (!req.getRequester().getId().equals(requester.getId()))
            throw new IllegalArgumentException("Not your request");

        if (it.getEquipment().getItemType() != ItemType.RETURNABLE)
            throw new IllegalArgumentException("Return not required for NON_RETURNABLE items");

        if (it.getStatus() != RequestItemStatus.ISSUED_CONFIRMED)
            throw new IllegalArgumentException("Item not in issued state");

        it.setStatus(RequestItemStatus.RETURN_REQUESTED);
        requestItemRepository.save(it);

        recomputeAndPersistRequestStatus(req);

        // Notify TO ← WRONG TARGET BEFORE (was notifying lecturer)
        User to = req.getLab().getTechnicalOfficer();
        if (to != null) {
            notificationService.notifyUser(
                    to,
                    NotificationType.RETURN_SUBMITTED,
                    "Equipment Item Return Submitted — Inspection Required",
                    requester.getFullName() +
                    (requester.getRegNo() != null ? " (" + requester.getRegNo() + ")" : "") +
                    " has submitted a return for \"" + it.getEquipment().getName() +
                    "\" (qty: " + it.getIssuedQty() + ") from request #" + req.getId() +
                    " at lab \"" + req.getLab().getName() + "\". Please inspect and verify.",
                    req.getId(), null
            );
        }

        return req;
    }

    // ─────────────────────────────────────────────────────────────
    // TO VERIFY RETURN
    // ─────────────────────────────────────────────────────────────

    @Transactional
    public EquipmentRequest toVerifyReturn(String toEmail, Long requestId, boolean damaged) {
        User to = userRepository.findByEmail(toEmail).orElseThrow();
        if (to.getRole() != Role.TO) throw new IllegalArgumentException("Only TO");

        EquipmentRequest req = equipmentRequestRepository.findById(requestId).orElseThrow();
        ensureToAllowedForLab(to, req.getLab());

        if (req.getStatus() != RequestStatus.RETURNED_PENDING_TO_VERIFY)
            throw new IllegalArgumentException("Not pending return verification");

        List<RequestItem> items = requestItemRepository.findByRequestId(req.getId());

        for (RequestItem it : items) {
            Equipment eq = it.getEquipment();
            if (eq.getItemType() == ItemType.RETURNABLE) {
                if (!damaged) {
                    eq.setAvailableQty(eq.getAvailableQty() + it.getIssuedQty());
                    equipmentRepository.save(eq);
                } else {
                    it.setDamaged(true);
                    requestItemRepository.save(it);
                }
                it.setReturned(true);
                requestItemRepository.save(it);
            }
        }

        if (damaged) {
            req.setStatus(RequestStatus.DAMAGED_REPORTED);
            equipmentRequestRepository.save(req);
            notificationService.notifyUser(
                    req.getRequester(),
                    NotificationType.DAMAGE_REPORTED,
                    "Damage Reported on Your Returned Equipment",
                    "Technical Officer " + to.getFullName() + " has inspected and reported damage on the equipment " +
                    "returned for request #" + req.getId() + " at lab \"" + req.getLab().getName() + "\". " +
                    "Please contact your department or lab supervisor for further instructions.",
                    req.getId(), null
            );
        } else {
            req.setStatus(RequestStatus.RETURNED_VERIFIED);
            equipmentRequestRepository.save(req);
            notificationService.notifyUser(
                    req.getRequester(),
                    NotificationType.RETURN_VERIFIED,
                    "Equipment Return Successfully Verified",
                    "Technical Officer " + to.getFullName() + " has verified the return of equipment for request #" +
                    req.getId() + " at lab \"" + req.getLab().getName() + "\". " +
                    "All items are accounted for. Thank you for returning the equipment on time.",
                    req.getId(), null
            );
        }

        return req;
    }

    @Transactional
    public EquipmentRequest toVerifyReturnItem(String toEmail, Long requestItemId, boolean damaged) {
        User to = userRepository.findByEmail(toEmail).orElseThrow();
        if (to.getRole() != Role.TO) throw new IllegalArgumentException("Only TO");

        RequestItem it = requestItemRepository.findById(requestItemId)
                .orElseThrow(() -> new IllegalArgumentException("Request item not found"));
        EquipmentRequest req = it.getRequest();

        ensureToAllowedForLab(to, req.getLab());

        if (it.getStatus() != RequestItemStatus.RETURN_REQUESTED)
            throw new IllegalArgumentException("Item not pending return verification");

        Equipment eq = it.getEquipment();
        if (eq.getItemType() != ItemType.RETURNABLE)
            throw new IllegalArgumentException("This item is not returnable");

        if (!damaged) {
            eq.setAvailableQty(eq.getAvailableQty() + it.getIssuedQty());
            equipmentRepository.save(eq);
            it.setReturned(true);
            it.setStatus(RequestItemStatus.RETURN_VERIFIED);
        } else {
            it.setDamaged(true);
            it.setReturned(true);
            it.setStatus(RequestItemStatus.DAMAGED_REPORTED);
        }
        requestItemRepository.save(it);

        recomputeAndPersistRequestStatus(req);

        if (damaged) {
            notificationService.notifyUser(
                    req.getRequester(),
                    NotificationType.DAMAGE_REPORTED,
                    "Damage Reported — \"" + eq.getName() + "\"",
                    "Technical Officer " + to.getFullName() + " has reported damage on \"" + eq.getName() +
                    "\" returned for request #" + req.getId() + ". " +
                    "Please contact your department or lab supervisor for further instructions.",
                    req.getId(), null
            );
        } else {
            notificationService.notifyUser(
                    req.getRequester(),
                    NotificationType.RETURN_VERIFIED,
                    "Equipment Item Return Verified",
                    "Technical Officer " + to.getFullName() + " has verified the return of \"" + eq.getName() +
                    "\" (qty: " + it.getIssuedQty() + ") for request #" + req.getId() + ". Thank you!",
                    req.getId(), null
            );
        }

        return req;
    }

    // ─────────────────────────────────────────────────────────────
    // TO ALL REQUESTS VIEW
    // ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<RequestSummaryDTO> toRequestsForTo(String toEmail) {
        User to = userRepository.findByEmail(toEmail)
                .orElseThrow(() -> new IllegalArgumentException("TO not found"));

        if (to.getRole() != Role.TO)
            throw new IllegalArgumentException("Only TO can view these requests");

        List<Lab> labs = labRepository.findByTechnicalOfficerId(to.getId());
        if (labs.isEmpty())
            throw new IllegalArgumentException("No labs assigned to this TO. Ask HOD to assign.");

        List<Long> labIds = labs.stream().map(Lab::getId).toList();

        return equipmentRequestRepository.findByLabIdInOrderByIdDesc(labIds)
                .stream()
                .map(this::mapToRequestSummaryDTO)
                .toList();
    }

    // ─────────────────────────────────────────────────────────────
    // LEGACY ENTITY METHODS (used by controllers)
    // ─────────────────────────────────────────────────────────────

    public List<EquipmentRequest> lecturerQueue(String lecturerEmail) {
        User lecturer = userRepository.findByEmail(lecturerEmail).orElseThrow();
        if (lecturer.getRole() != Role.LECTURER && lecturer.getRole() != Role.HOD)
            throw new IllegalArgumentException("Only lecturer or HOD");
        return equipmentRequestRepository.findByLecturerIdAndStatusOrderByIdDesc(
                lecturer.getId(), RequestStatus.PENDING_LECTURER_APPROVAL);
    }

    public List<EquipmentRequest> toApprovedRequestsForLab(String toEmail, Long labId) {
        User to = userRepository.findByEmail(toEmail).orElseThrow();
        if (to.getRole() != Role.TO) throw new IllegalArgumentException("Only TO");
        Lab lab = labRepository.findById(labId)
                .orElseThrow(() -> new IllegalArgumentException("Lab not found"));
        ensureToAllowedForLab(to, lab);
        return equipmentRequestRepository.findByLabIdAndStatusOrderByIdDesc(labId, RequestStatus.APPROVED_BY_LECTURER);
    }

    // ─────────────────────────────────────────────────────────────
    // DTO ENDPOINT WRAPPERS
    // ─────────────────────────────────────────────────────────────

    @Transactional
    public ToIssueResponseDTO toIssueDTO(String toEmail, Long requestId) {
        return mapToIssueResponseDTO(toIssue(toEmail, requestId));
    }

    @Transactional
    public ToIssueResponseDTO toIssueItemDTO(String toEmail, Long requestItemId) {
        return mapToIssueResponseDTO(toIssueItem(toEmail, requestItemId));
    }

    @Transactional
    public StudentAcceptanceDTO studentAcceptIssueDTO(String requesterEmail, Long requestId) {
        return mapToStudentAcceptanceDTO(studentAcceptIssue(requesterEmail, requestId));
    }

    @Transactional
    public StudentAcceptanceDTO studentAcceptIssueItemDTO(String requesterEmail, Long requestItemId) {
        return mapToStudentAcceptanceDTO(studentAcceptIssueItem(requesterEmail, requestItemId));
    }

    @Transactional
    public StudentReturnDTO submitReturnDTO(String requesterEmail, Long requestId) {
        return mapToStudentReturnDTO(submitReturn(requesterEmail, requestId));
    }

    @Transactional
    public StudentReturnDTO submitReturnItemDTO(String requesterEmail, Long requestItemId) {
        return mapToStudentReturnDTO(submitReturnItem(requesterEmail, requestItemId));
    }

    @Transactional
    public ToVerifyReturnResponseDTO toVerifyReturnDTO(String toEmail, Long requestId, boolean damaged) {
        return mapToVerifyReturnDTO(toVerifyReturn(toEmail, requestId, damaged), damaged);
    }

    @Transactional
    public ToVerifyReturnResponseDTO toVerifyReturnItemDTO(String toEmail, Long requestItemId, boolean damaged) {
        return mapToVerifyReturnDTO(toVerifyReturnItem(toEmail, requestItemId, damaged), damaged);
    }

    // ─────────────────────────────────────────────────────────────
    // RECOMPUTE REQUEST STATUS FROM ITEMS
    // ─────────────────────────────────────────────────────────────

    private void recomputeAndPersistRequestStatus(EquipmentRequest req) {
        List<RequestItem> items = requestItemRepository.findByRequestId(req.getId());
        if (items.isEmpty()) return;

        boolean anyPendingLecturer  = items.stream().anyMatch(i -> i.getStatus() == RequestItemStatus.PENDING_LECTURER_APPROVAL);
        boolean anyApproved         = items.stream().anyMatch(i -> i.getStatus() == RequestItemStatus.APPROVED_BY_LECTURER);
        boolean allRejected         = items.stream().allMatch(i -> i.getStatus() == RequestItemStatus.REJECTED_BY_LECTURER);
        boolean anyIssuedPending    = items.stream().anyMatch(i -> i.getStatus() == RequestItemStatus.ISSUED_PENDING_REQUESTER_ACCEPT);
        boolean anyIssuedConfirmed  = items.stream().anyMatch(i -> i.getStatus() == RequestItemStatus.ISSUED_CONFIRMED);
        boolean anyReturnRequested  = items.stream().anyMatch(i -> i.getStatus() == RequestItemStatus.RETURN_REQUESTED);
        boolean anyDamaged          = items.stream().anyMatch(i -> i.getStatus() == RequestItemStatus.DAMAGED_REPORTED);

        boolean allReturnDoneOrNonReturnable = items.stream().allMatch(i -> {
            ItemType t = i.getEquipment().getItemType();
            if (t == ItemType.NON_RETURNABLE) return true;
            return i.getStatus() == RequestItemStatus.RETURN_VERIFIED || i.getStatus() == RequestItemStatus.DAMAGED_REPORTED;
        });

        RequestStatus newStatus;
        if (anyPendingLecturer) {
            newStatus = RequestStatus.PENDING_LECTURER_APPROVAL;
        } else if (allRejected) {
            newStatus = RequestStatus.REJECTED_BY_LECTURER;
        } else if (anyReturnRequested) {
            newStatus = RequestStatus.RETURNED_PENDING_TO_VERIFY;
        } else if (allReturnDoneOrNonReturnable && anyIssuedConfirmed) {
            newStatus = anyDamaged ? RequestStatus.DAMAGED_REPORTED : RequestStatus.RETURNED_VERIFIED;
        } else if (anyIssuedPending) {
            newStatus = RequestStatus.ISSUED_PENDING_STUDENT_ACCEPT;
        } else if (anyIssuedConfirmed) {
            newStatus = RequestStatus.ISSUED_CONFIRMED;
        } else if (anyApproved) {
            newStatus = RequestStatus.APPROVED_BY_LECTURER;
        } else {
            newStatus = req.getStatus();
        }

        if (req.getStatus() != newStatus) {
            req.setStatus(newStatus);
            equipmentRequestRepository.save(req);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // DTO MAPPERS
    // ─────────────────────────────────────────────────────────────

    private RequestSummaryDTO mapToRequestSummaryDTO(EquipmentRequest req) {
        List<RequestItem> items = requestItemRepository.findByRequestId(req.getId());
        List<RequestSummaryItemDTO> itemDtos = items.stream().map(ri -> {
            Equipment e = ri.getEquipment();
            return new RequestSummaryItemDTO(
                    ri.getId(), e.getId(), e.getName(), e.getCategory(),
                    e.getItemType().name(), ri.getQuantity(),
                    ri.getStatus() == null ? null : ri.getStatus().name(),
                    ri.getToWaitReason(), ri.getIssuedQty(), ri.isReturned(), ri.isDamaged()
            );
        }).toList();

        return new RequestSummaryDTO(
                req.getId(), req.getStatus().name(), req.getPurpose(),
                req.getFromDate(), req.getToDate(), req.getFromTime(), req.getToTime(),
                req.getLab().getName(), req.getLab().getDepartment(),
                req.getLecturer().getFullName(), req.getRequester().getFullName(),
                req.getRequester().getRegNo(), req.getRequester().getRole().name(),
                itemDtos
        );
    }

    private ToApprovedRequestDTO mapToApprovedRequestDTO(EquipmentRequest req) {
        User requester = req.getRequester();
        User lecturer  = req.getLecturer();
        Lab  lab       = req.getLab();

        List<RequestItem> items = requestItemRepository.findByRequestId(req.getId());
        List<ToApprovedRequestItemDTO> itemDtos = items.stream().map(ri -> {
            Equipment e = ri.getEquipment();
            return new ToApprovedRequestItemDTO(
                    ri.getId(), e.getId(), e.getName(), e.getCategory(),
                    e.getItemType().name(), ri.getQuantity(), e.getAvailableQty(),
                    ri.getStatus() == null ? null : ri.getStatus().name(),
                    ri.getToWaitReason(), ri.getIssuedQty(), ri.isReturned(), ri.isDamaged()
            );
        }).toList();

        return new ToApprovedRequestDTO(
                req.getId(), req.getStatus().name(), req.getPriorityScore(), req.getPurpose(),
                req.getFromDate(), req.getToDate(),
                requester.getId(), requester.getEmail(), requester.getFullName(),
                requester.getRegNo(), requester.getDepartment(),
                lab.getId(), lab.getName(), lab.getDepartment(),
                lecturer.getId(), lecturer.getFullName(), lecturer.getDepartment(),
                itemDtos
        );
    }

    private ToIssueResponseDTO mapToIssueResponseDTO(EquipmentRequest req) {
        List<RequestItem> items = requestItemRepository.findByRequestId(req.getId());
        List<IssuedItemDTO> issuedItems = items.stream().map(ri -> new IssuedItemDTO(
                ri.getEquipment().getId(), ri.getEquipment().getName(),
                ri.getIssuedQty(), ri.getEquipment().getItemType().name()
        )).toList();
        return new ToIssueResponseDTO(
                req.getId(), req.getStatus().name(), req.getLab().getName(), req.getLab().getDepartment(),
                req.getRequester().getFullName(), req.getRequester().getRegNo(),
                req.getLecturer().getFullName(), req.getFromDate(), req.getToDate(), issuedItems
        );
    }

    private StudentAcceptanceDTO mapToStudentAcceptanceDTO(EquipmentRequest req) {
        return new StudentAcceptanceDTO(
                req.getId(), req.getStatus().name(), "Issue accepted successfully",
                req.getLab().getName(), req.getLab().getDepartment(),
                req.getLecturer().getFullName(), req.getFromDate(), req.getToDate()
        );
    }

    private StudentReturnDTO mapToStudentReturnDTO(EquipmentRequest req) {
        return new StudentReturnDTO(
                req.getId(), req.getStatus().name(), "Return submitted successfully",
                req.getLab().getName(), req.getLab().getDepartment(),
                req.getFromDate(), req.getToDate()
        );
    }

    private ToVerifyReturnResponseDTO mapToVerifyReturnDTO(EquipmentRequest req, boolean damagedFlag) {
        List<RequestItem> items = requestItemRepository.findByRequestId(req.getId());
        List<VerifiedReturnItemDTO> itemDtos = items.stream().map(ri -> new VerifiedReturnItemDTO(
                ri.getEquipment().getId(), ri.getEquipment().getName(),
                ri.getIssuedQty(), damagedFlag, ri.getEquipment().getItemType().name()
        )).toList();
        String msg = damagedFlag ? "Return verified with damage reported" : "Return verified successfully";
        return new ToVerifyReturnResponseDTO(req.getId(), req.getStatus().name(), msg, damagedFlag, itemDtos);
    }

    private StudentMyRequestDTO mapToStudentMyRequestDTO(EquipmentRequest req) {
        List<RequestItem> items = requestItemRepository.findByRequestId(req.getId());
        List<StudentMyRequestItemDTO> itemDtos = items.stream().map(ri ->
                new StudentMyRequestItemDTO(
                        ri.getId(), ri.getEquipment().getId(), ri.getEquipment().getName(),
                        ri.getQuantity(), ri.getEquipment().getItemType().name(),
                        ri.getStatus() == null ? null : ri.getStatus().name(),
                        ri.getToWaitReason(), ri.getIssuedQty(), ri.isReturned(), ri.isDamaged()
                )
        ).toList();

        boolean hasReturnable = items.stream()
                .anyMatch(i -> i.getEquipment().getItemType() == ItemType.RETURNABLE);
        boolean canAcceptIssue = req.getStatus() == RequestStatus.ISSUED_PENDING_STUDENT_ACCEPT;
        boolean canReturn = req.getStatus() == RequestStatus.ISSUED_CONFIRMED && hasReturnable;

        return new StudentMyRequestDTO(
                req.getId(), req.getStatus().name(), req.getPurpose(),
                req.getFromDate(), req.getToDate(), req.getFromTime(), req.getToTime(),
                req.getLab().getName(), req.getLecturer().getFullName(),
                itemDtos, canAcceptIssue, canReturn
        );
    }

    // ─────────────────────────────────────────────────────────────
    // GUARDS
    // ─────────────────────────────────────────────────────────────

    private void ensureToAllowedForLab(User to, Lab lab) {
        if (to.getRole() != Role.TO)
            throw new IllegalArgumentException("Only TO can perform this action");
        if (lab == null)
            throw new IllegalArgumentException("Request has no lab");
        if (!com.uoj.equipment.util.DepartmentUtil.equalsNormalized(lab.getDepartment(), to.getDepartment()))
            throw new IllegalArgumentException("TO department mismatch with lab");
        User assignedTo = lab.getTechnicalOfficer();
        if (assignedTo == null || assignedTo.getId() == null)
            throw new IllegalArgumentException("No TO assigned to this lab. Ask HOD to assign.");
        if (!assignedTo.getId().equals(to.getId()))
            throw new IllegalArgumentException("You are not the assigned TO for this lab");
    }
}