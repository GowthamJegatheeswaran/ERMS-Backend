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


    // LECTURER QUEUE

    public List<RequestSummaryDTO> lecturerQueueDTO(String lecturerEmail) {
        User lecturer = userRepository.findByEmail(lecturerEmail).orElseThrow();
        if (lecturer.getRole() != Role.LECTURER && lecturer.getRole() != Role.HOD) {
            throw new IllegalArgumentException("Only lecturer or HOD");
        }

        return equipmentRequestRepository.findByLecturerIdAndStatusOrderByIdDesc(
                        lecturer.getId(),
                        RequestStatus.PENDING_LECTURER_APPROVAL
                ).stream()
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


    // CREATE REQUEST
    @Transactional
    public RequestSummaryDTO createRequestAndReturnDTO(String requesterEmail, NewRequestDTO dto) {
        EquipmentRequest saved = createRequest(requesterEmail, dto);
        return mapToRequestSummaryDTO(saved);
    }


    // STUDENT MY REQUESTS
    public List<StudentMyRequestDTO> myRequestsStudentView(String requesterEmail) {
        User requester = userRepository.findByEmail(requesterEmail).orElseThrow();
        return equipmentRequestRepository.findByRequesterIdOrderByIdDesc(requester.getId())
                .stream()
                .map(this::mapToStudentMyRequestDTO)
                .toList();
    }


    // TO APPROVED REQUESTS (CLEAN VIEW)
    public List<ToApprovedRequestDTO> toApprovedRequestsForLabView(String toEmail, Long labId) {
        User to = userRepository.findByEmail(toEmail).orElseThrow();
        if (to.getRole() != Role.TO) throw new IllegalArgumentException("Only TO");

        Lab lab = labRepository.findById(labId)
                .orElseThrow(() -> new IllegalArgumentException("Lab not found"));

        // Check this TO is assigned to this lab
        ensureToAllowedForLab(to, lab);

        List<EquipmentRequest> list = equipmentRequestRepository.findByLabIdAndStatusOrderByIdDesc(
                labId, RequestStatus.APPROVED_BY_LECTURER
        );

        return list.stream().map(this::mapToApprovedRequestDTO).toList();
    }




    // Supports Lecturer self-request:
    @Transactional
    public EquipmentRequest createRequest(String requesterEmail, NewRequestDTO dto) {

        User requester = userRepository.findByEmail(requesterEmail).orElseThrow();

        if (requester.getRole() != Role.STUDENT &&
    requester.getRole() != Role.STAFF &&
    requester.getRole() != Role.LECTURER &&
    requester.getRole() != Role.HOD) {   // added HOD
    throw new IllegalArgumentException("Not allowed to create request");
}

        if (dto.getLabId() == null) throw new IllegalArgumentException("labId required");
        if (dto.getItems() == null || dto.getItems().isEmpty())
            throw new IllegalArgumentException("At least 1 item required");

        Lab lab = labRepository.findById(dto.getLabId())
                .orElseThrow(() -> new IllegalArgumentException("Lab not found"));

        // Lecturer selection
        User lecturer;
        if (requester.getRole() == Role.LECTURER) {
            lecturer = requester;
        } else {
            if (dto.getLecturerId() == null) {
                throw new IllegalArgumentException("lecturerId required");
            }
            lecturer = userRepository.findById(dto.getLecturerId())
                    .orElseThrow(() -> new IllegalArgumentException("Lecturer not found"));
            if (lecturer.getRole() != Role.LECTURER && lecturer.getRole() != Role.HOD)
    throw new IllegalArgumentException("Invalid lecturer");

            // Lecturer dept must match lab dept
            if (lab.getDepartment() == null || lecturer.getDepartment() == null) {
                throw new IllegalArgumentException("Department information missing for lab/lecturer");
            }
            if (!com.uoj.equipment.util.DepartmentUtil.equalsNormalized(lecturer.getDepartment(), lab.getDepartment())) {
                throw new IllegalArgumentException("Lecturer department mismatch");
            }
        }

        int priorityScore = priorityService.calculate(
                dto.getPurpose(),       // now PurposeType
                dto.getFromDate(),
                dto.getToDate(),
                false
        );


        EquipmentRequest req = new EquipmentRequest();
        req.setRequester(requester);
        req.setLecturer(lecturer);
        req.setLab(lab);
        req.setPurpose(dto.getPurpose());
        req.setFromDate(dto.getFromDate());
        req.setToDate(dto.getToDate());
        req.setLetterAttachmentPath(dto.getLetterAttachmentPath());
        req.setPriorityScore(priorityScore);

        if (requester.getRole() == Role.LECTURER || requester.getRole() == Role.HOD) {
    req.setStatus(RequestStatus.APPROVED_BY_LECTURER);
} else {
    req.setStatus(RequestStatus.PENDING_LECTURER_APPROVAL);
}

        EquipmentRequest saved = equipmentRequestRepository.save(req);

        for (NewRequestDTO.ItemLine line : dto.getItems()) {
            if (line.getEquipmentId() == null) throw new IllegalArgumentException("equipmentId required");
            if (line.getQuantity() <= 0) throw new IllegalArgumentException("Invalid quantity");

            Equipment eq = equipmentRepository.findById(line.getEquipmentId())
                    .orElseThrow(() -> new IllegalArgumentException("Equipment not found"));

            if (!eq.isActive()) {
                throw new IllegalArgumentException("Equipment inactive: " + eq.getId());
            }

            if (eq.getLab() == null || eq.getLab().getId() == null ||
                    !eq.getLab().getId().equals(lab.getId())) {
                throw new IllegalArgumentException("Equipment not in selected lab: " + eq.getId());
            }

            RequestItem item = new RequestItem();
            item.setRequest(saved);
            item.setEquipment(eq);
            item.setQuantity(line.getQuantity());
            item.setIssuedQty(0);
            item.setReturned(false);
            item.setDamaged(false);

            // Per-item workflow status
            if (requester.getRole() == Role.LECTURER || requester.getRole() == Role.HOD) {
    item.setStatus(RequestItemStatus.APPROVED_BY_LECTURER);
} else {
    item.setStatus(RequestItemStatus.PENDING_LECTURER_APPROVAL);
}

            requestItemRepository.save(item);
        }

        // Notifications:
        if (requester.getRole() == Role.STUDENT || requester.getRole() == Role.STAFF) {
            // notify requester
            notificationService.notifyUser(
                    requester,
                    NotificationType.REQUEST_SUBMITTED,
                    "Request submitted",
                    "Your equipment request has been submitted.",
                    saved.getId(),
                    null
            );

            // notify lecturer
            notificationService.notifyUser(
                    lecturer,
                    NotificationType.REQUEST_SUBMITTED,
                    "New equipment request",
                    "New request from: " + requester.getFullName(),
                    saved.getId(),
                    null
            );
        }
        //LECTURER REQUEST
        if (requester.getRole() == Role.LECTURER) {
            notificationService.notifyUser(
                    requester,
                    NotificationType.REQUEST_SUBMITTED,
                    "Your request created",
                    "You created a new equipment request for lab " + lab.getName(),
                    saved.getId(),
                    null
            );
        }



        return saved;
    }


    // ------------------------
    // Per-item workflow helpers
    // ------------------------

    /**
     * Recompute header-level request status from the set of item statuses.
     * Dashboards still group by request-level status, while actions happen per item.
     */
    private void recomputeAndPersistRequestStatus(EquipmentRequest req) {
        List<RequestItem> items = requestItemRepository.findByRequestId(req.getId());
        if (items.isEmpty()) return;

        boolean anyPendingLecturer = items.stream().anyMatch(i -> i.getStatus() == RequestItemStatus.PENDING_LECTURER_APPROVAL);
        boolean anyApproved = items.stream().anyMatch(i -> i.getStatus() == RequestItemStatus.APPROVED_BY_LECTURER);
        boolean allRejected = items.stream().allMatch(i -> i.getStatus() == RequestItemStatus.REJECTED_BY_LECTURER);

        boolean anyIssuedPendingAccept = items.stream().anyMatch(i -> i.getStatus() == RequestItemStatus.ISSUED_PENDING_REQUESTER_ACCEPT);
        boolean anyIssuedConfirmed = items.stream().anyMatch(i -> i.getStatus() == RequestItemStatus.ISSUED_CONFIRMED);
        boolean anyReturnRequested = items.stream().anyMatch(i -> i.getStatus() == RequestItemStatus.RETURN_REQUESTED);
        boolean anyDamaged = items.stream().anyMatch(i -> i.getStatus() == RequestItemStatus.DAMAGED_REPORTED);

        boolean allReturnVerifiedOrNonReturnable = items.stream().allMatch(i -> {
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
        } else if (allReturnVerifiedOrNonReturnable && anyIssuedConfirmed) {
            newStatus = anyDamaged ? RequestStatus.DAMAGED_REPORTED : RequestStatus.RETURNED_VERIFIED;
        } else if (anyIssuedPendingAccept) {
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

    // Lecturer queue (ENTITY)

    public List<EquipmentRequest> lecturerQueue(String lecturerEmail) {
        User lecturer = userRepository.findByEmail(lecturerEmail).orElseThrow();
        if (lecturer.getRole() != Role.LECTURER && lecturer.getRole() != Role.HOD) {
            throw new IllegalArgumentException("Only lecturer or HOD");
        }

        return equipmentRequestRepository.findByLecturerIdAndStatusOrderByIdDesc(
                lecturer.getId(), RequestStatus.PENDING_LECTURER_APPROVAL
        );
    }

    @Transactional
    public EquipmentRequest lecturerApprove(String lecturerEmail, Long requestId) {
        User lecturer = userRepository.findByEmail(lecturerEmail).orElseThrow();
        if (lecturer.getRole() != Role.LECTURER && lecturer.getRole() != Role.HOD) {
            throw new IllegalArgumentException("Only lecturer or HOD");
        }

        EquipmentRequest req = equipmentRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found"));

        if (req.getLecturer() == null || !req.getLecturer().getId().equals(lecturer.getId()))
            throw new IllegalArgumentException("Not your request");

        if (req.getStatus() != RequestStatus.PENDING_LECTURER_APPROVAL)
            throw new IllegalArgumentException("Not in approval state");

        // Backward-compatible: approve ALL pending items in the request
        List<RequestItem> items = requestItemRepository.findByRequestId(req.getId());
        for (RequestItem it : items) {
            if (it.getStatus() == RequestItemStatus.PENDING_LECTURER_APPROVAL) {
                it.setStatus(RequestItemStatus.APPROVED_BY_LECTURER);
                requestItemRepository.save(it);
            }
        }

        recomputeAndPersistRequestStatus(req);

        notificationService.notifyUser(
                req.getRequester(),
                NotificationType.REQUEST_APPROVED,
                "Request approved",
                "Your request was approved by " + lecturer.getFullName(),
                req.getId(),
                null
        );


        return req;
    }

    @Transactional
    public EquipmentRequest lecturerReject(String lecturerEmail, Long requestId, String reason) {
        User lecturer = userRepository.findByEmail(lecturerEmail).orElseThrow();
        if (lecturer.getRole() != Role.LECTURER && lecturer.getRole() != Role.HOD) {
            throw new IllegalArgumentException("Only lecturer or HOD");
        }

        EquipmentRequest req = equipmentRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found"));

        if (req.getLecturer() == null || !req.getLecturer().getId().equals(lecturer.getId()))
            throw new IllegalArgumentException("Not your request");

        if (req.getStatus() != RequestStatus.PENDING_LECTURER_APPROVAL)
            throw new IllegalArgumentException("Not in approval state");

        // Backward-compatible: reject ALL pending items in the request
        List<RequestItem> items = requestItemRepository.findByRequestId(req.getId());
        for (RequestItem it : items) {
            if (it.getStatus() == RequestItemStatus.PENDING_LECTURER_APPROVAL) {
                it.setStatus(RequestItemStatus.REJECTED_BY_LECTURER);
                requestItemRepository.save(it);
            }
        }

        recomputeAndPersistRequestStatus(req);

        String msg = "Your request was rejected by " + lecturer.getFullName();
        if (reason != null && !reason.isBlank()) msg += ". Reason: " + reason;

        notificationService.notifyUser(
                req.getRequester(),
                NotificationType.REQUEST_REJECTED,
                "Request rejected",
                msg,
                req.getId(),
                null
        );

        return req;
    }


    // Lecturer approves a SINGLE request item (equipment line)
    @Transactional
    public EquipmentRequest lecturerApproveItem(String lecturerEmail, Long requestItemId) {
        User lecturer = userRepository.findByEmail(lecturerEmail).orElseThrow();
        if (lecturer.getRole() != Role.LECTURER && lecturer.getRole() != Role.HOD) {
            throw new IllegalArgumentException("Only lecturer or HOD");
        }

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

        notificationService.notifyUser(
                req.getRequester(),
                NotificationType.REQUEST_APPROVED,
                "Request item approved",
                "An item in your request was approved by " + lecturer.getFullName(),
                req.getId(),
                null
        );

        return req;
    }

    // Lecturer rejects a SINGLE request item (equipment line)
    @Transactional
    public EquipmentRequest lecturerRejectItem(String lecturerEmail, Long requestItemId, String reason) {
        User lecturer = userRepository.findByEmail(lecturerEmail).orElseThrow();
        if (lecturer.getRole() != Role.LECTURER && lecturer.getRole() != Role.HOD) {
            throw new IllegalArgumentException("Only lecturer or HOD");
        }

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

        String msg = "An item in your request was rejected by " + lecturer.getFullName();
        if (reason != null && !reason.isBlank()) msg += ". Reason: " + reason;

        notificationService.notifyUser(
                req.getRequester(),
                NotificationType.REQUEST_REJECTED,
                "Request item rejected",
                msg,
                req.getId(),
                null
        );

        return req;
    }


    // TO approved requests

    public List<EquipmentRequest> toApprovedRequestsForLab(String toEmail, Long labId) {
        User to = userRepository.findByEmail(toEmail).orElseThrow();
        if (to.getRole() != Role.TO) throw new IllegalArgumentException("Only TO");

        Lab lab = labRepository.findById(labId)
                .orElseThrow(() -> new IllegalArgumentException("Lab not found"));

        //  new check: is this TO assigned to this lab?
        ensureToAllowedForLab(to, lab);

        return equipmentRequestRepository.findByLabIdAndStatusOrderByIdDesc(
                labId, RequestStatus.APPROVED_BY_LECTURER
        );
    }



    // TO issues equipment

    @Transactional
    public EquipmentRequest toIssue(String toEmail, Long requestId) {
        User to = userRepository.findByEmail(toEmail).orElseThrow();
        if (to.getRole() != Role.TO) throw new IllegalArgumentException("Only TO");

        EquipmentRequest req = equipmentRequestRepository.findById(requestId).orElseThrow();

        // this TO is assigned for this lab
        ensureToAllowedForLab(to, req.getLab());

        if (req.getStatus() != RequestStatus.APPROVED_BY_LECTURER)
            throw new IllegalArgumentException("Request not ready for issue");

        List<RequestItem> items = requestItemRepository.findByRequestId(req.getId());
        if (items.isEmpty()) throw new IllegalArgumentException("No request items");

        // Verify availability + stock
        for (RequestItem it : items) {
            Equipment eq = it.getEquipment();
            int need = it.getQuantity();
            if (eq.getAvailableQty() < need) {
                throw new IllegalArgumentException("Not enough stock for: " + eq.getName());
            }
        }

        for (RequestItem it : items) {
            Equipment eq = it.getEquipment();
            int need = it.getQuantity();
            eq.setAvailableQty(eq.getAvailableQty() - need);
            equipmentRepository.save(eq);

            it.setIssuedQty(need);
            requestItemRepository.save(it);
        }

        req.setStatus(RequestStatus.ISSUED_PENDING_STUDENT_ACCEPT);
        equipmentRequestRepository.save(req);

        notificationService.notifyUser(
                req.getRequester(),
                NotificationType.ISSUE_READY,
                "Equipment issued - please accept",
                "TO issued equipment for your request. Please accept to confirm.",
                req.getId(),
                null
        );

        return req;
    }


    // TO issues a SINGLE request item
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
        int need = it.getQuantity();
        if (eq.getAvailableQty() < need) {
            throw new IllegalArgumentException("Not enough stock for: " + eq.getName());
        }

        eq.setAvailableQty(eq.getAvailableQty() - need);
        equipmentRepository.save(eq);

        it.setIssuedQty(need);
        it.setStatus(RequestItemStatus.ISSUED_PENDING_REQUESTER_ACCEPT);
        it.setToWaitReason(null);
        requestItemRepository.save(it);

        recomputeAndPersistRequestStatus(req);

        notificationService.notifyUser(
                req.getRequester(),
                NotificationType.ISSUE_READY,
                "Equipment issued - please accept",
                "TO issued an item for your request. Please accept to confirm.",
                req.getId(),
                null
        );

        return req;
    }


    // TO marks a SINGLE request item as waiting (with a reason)
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

        notificationService.notifyUser(
                req.getRequester(),
                NotificationType.TO_WAIT,
                "Item waiting for issue",
                (it.getToWaitReason() == null || it.getToWaitReason().isBlank())
                        ? "TO marked an item as waiting for issue."
                        : "TO marked an item as waiting: " + it.getToWaitReason(),
                req.getId(),
                null
        );

        return req;
    }



    // Student/Staff accepts issue

    @Transactional
    public EquipmentRequest studentAcceptIssue(String requesterEmail, Long requestId) {
        User requester = userRepository.findByEmail(requesterEmail).orElseThrow();
        if (requester.getRole() != Role.STUDENT && requester.getRole() != Role.STAFF && requester.getRole() != Role.LECTURER)
            throw new IllegalArgumentException("Only requester can accept");

        EquipmentRequest req = equipmentRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found"));

        if (!req.getRequester().getId().equals(requester.getId()))
            throw new IllegalArgumentException("Not your request");

        if (req.getStatus() != RequestStatus.ISSUED_PENDING_STUDENT_ACCEPT)
            throw new IllegalArgumentException("Not waiting for acceptance");

        req.setStatus(RequestStatus.ISSUED_CONFIRMED);
        equipmentRequestRepository.save(req);

        notificationService.notifyUser(
                req.getLecturer(),
                NotificationType.ISSUE_ACCEPTED,
                "Issue accepted",
                "Requester accepted issued equipment for request id: " + req.getId(),
                req.getId(),
                null
        );
        //  notify lecturer
        notificationService.notifyUser(
                req.getLecturer(),
                NotificationType.ISSUE_ACCEPTED,
                "Equipment issued",
                "TO has issued equipment for a request you approved.",
                req.getId(),
                null
        );

        return req;
    }


    // Student/Staff accepts issue for a SINGLE request item
    @Transactional
    public EquipmentRequest studentAcceptIssueItem(String requesterEmail, Long requestItemId) {
        User requester = userRepository.findByEmail(requesterEmail).orElseThrow();
        if (requester.getRole() != Role.STUDENT && requester.getRole() != Role.STAFF && requester.getRole() != Role.LECTURER)
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

        notificationService.notifyUser(
                req.getLecturer(),
                NotificationType.ISSUE_ACCEPTED,
                "Issue accepted",
                "Requester accepted an issued item for request id: " + req.getId(),
                req.getId(),
                null
        );

        return req;
    }


    // Student submits return

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

        if (!hasReturnable) {
            throw new IllegalArgumentException("Return not required for NON_RETURNABLE items");
        }

        req.setStatus(RequestStatus.RETURNED_PENDING_TO_VERIFY);
        equipmentRequestRepository.save(req);

        notificationService.notifyUser(
                req.getLecturer(),
                NotificationType.RETURN_SUBMITTED,
                "Return submitted",
                "Requester submitted return for verification. Request id: " + req.getId(),
                req.getId(),
                null
        );

        return req;
    }


    // Student submits return for a SINGLE request item
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

        notificationService.notifyUser(
                req.getLecturer(),
                NotificationType.RETURN_SUBMITTED,
                "Return submitted",
                "Requester submitted return for verification. Request id: " + req.getId(),
                req.getId(),
                null
        );

        return req;
    }


    // TO verifies return

    @Transactional
    public EquipmentRequest toVerifyReturn(String toEmail, Long requestId, boolean damaged) {
        User to = userRepository.findByEmail(toEmail).orElseThrow();
        if (to.getRole() != Role.TO) throw new IllegalArgumentException("Only TO");

        EquipmentRequest req = equipmentRequestRepository.findById(requestId).orElseThrow();

        //   this TO is the assigned TO for the lab
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
                    "Damage reported",
                    "TO marked returned equipment as damaged for request id: " + req.getId(),
                    req.getId(),
                    null
            );
        } else {
            req.setStatus(RequestStatus.RETURNED_VERIFIED);
            equipmentRequestRepository.save(req);

            notificationService.notifyUser(
                    req.getRequester(),
                    NotificationType.RETURN_VERIFIED,
                    "Return verified",
                    "TO verified return for request id: " + req.getId(),
                    req.getId(),
                    null
            );
        }

        return req;
    }


    // TO verifies return for a SINGLE request item
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
        if (eq.getItemType() != ItemType.RETURNABLE) {
            throw new IllegalArgumentException("This item is not returnable");
        }

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

        notificationService.notifyUser(
                req.getRequester(),
                damaged ? NotificationType.DAMAGE_REPORTED : NotificationType.RETURN_VERIFIED,
                damaged ? "Damage reported" : "Return verified",
                damaged
                        ? "TO marked returned equipment as damaged for request id: " + req.getId()
                        : "TO verified return for request id: " + req.getId(),
                req.getId(),
                null
        );

        return req;
    }



    // DTO MAPPERS
    private RequestSummaryDTO mapToRequestSummaryDTO(EquipmentRequest req) {
        User requester = req.getRequester();
        User lecturer = req.getLecturer();
        Lab lab = req.getLab();

        List<RequestItem> items = requestItemRepository.findByRequestId(req.getId());
        List<RequestSummaryItemDTO> itemDtos = items.stream().map(ri -> {
            Equipment e = ri.getEquipment();
            return new RequestSummaryItemDTO(
                    ri.getId(),
                    e.getId(),
                    e.getName(),
                    e.getCategory(),
                    e.getItemType().name(),
                    ri.getQuantity(),
                    ri.getStatus() == null ? null : ri.getStatus().name(),
                    ri.getToWaitReason(),
                    ri.getIssuedQty(),
                    ri.isReturned(),
                    ri.isDamaged()
            );
        }).toList();

        return new RequestSummaryDTO(
                req.getId(),
                req.getStatus().name(),
                req.getPurpose(),
                req.getFromDate(),
                req.getToDate(),
                req.getLab().getName(),
                req.getLab().getDepartment(),
                req.getLecturer().getFullName(),
                req.getRequester().getFullName(),
                req.getRequester().getRegNo(),
                req.getRequester().getRole().name(),
                itemDtos
        );
    }

    private ToApprovedRequestDTO mapToApprovedRequestDTO(EquipmentRequest req) {
        User requester = req.getRequester();
        User lecturer = req.getLecturer();
        Lab lab = req.getLab();

        List<RequestItem> items = requestItemRepository.findByRequestId(req.getId());

        List<ToApprovedRequestItemDTO> itemDtos = items.stream().map(ri -> {
            Equipment e = ri.getEquipment();
            return new ToApprovedRequestItemDTO(
                    ri.getId(),
                    e.getId(),
                    e.getName(),
                    e.getCategory(),
                    e.getItemType().name(),
                    ri.getQuantity(),
                    e.getAvailableQty(),
                    ri.getStatus() == null ? null : ri.getStatus().name(),
                    ri.getToWaitReason(),
                    ri.getIssuedQty(),
                    ri.isReturned(),
                    ri.isDamaged()
            );
        }).toList();

        return new ToApprovedRequestDTO(
                req.getId(),
                req.getStatus().name(),
                req.getPriorityScore(),
                req.getPurpose(),
                req.getFromDate(),
                req.getToDate(),

                requester.getId(),
                requester.getEmail(),
                requester.getFullName(),
                requester.getRegNo(),
                requester.getDepartment(),

                lab.getId(),
                lab.getName(),
                lab.getDepartment(),

                lecturer.getId(),
                lecturer.getFullName(),
                lecturer.getDepartment(),

                itemDtos
        );
    }

    private ToIssueResponseDTO mapToIssueResponseDTO(EquipmentRequest req) {
        List<RequestItem> items = requestItemRepository.findByRequestId(req.getId());

        List<IssuedItemDTO> issuedItems = items.stream().map(ri -> new IssuedItemDTO(
                ri.getEquipment().getId(),
                ri.getEquipment().getName(),
                ri.getIssuedQty(),
                ri.getEquipment().getItemType().name()
        )).toList();

        return new ToIssueResponseDTO(
                req.getId(),
                req.getStatus().name(),
                req.getLab().getName(),
                req.getLab().getDepartment(),
                req.getRequester().getFullName(),
                req.getRequester().getRegNo(),
                req.getLecturer().getFullName(),
                req.getFromDate(),
                req.getToDate(),
                issuedItems
        );
    }

    private StudentAcceptanceDTO mapToStudentAcceptanceDTO(EquipmentRequest req) {
        return new StudentAcceptanceDTO(
                req.getId(),
                req.getStatus().name(),
                "Issue accepted successfully",
                req.getLab().getName(),
                req.getLab().getDepartment(),
                req.getLecturer().getFullName(),
                req.getFromDate(),
                req.getToDate()
        );
    }

    private StudentReturnDTO mapToStudentReturnDTO(EquipmentRequest req) {
        return new StudentReturnDTO(
                req.getId(),
                req.getStatus().name(),
                "Return submitted successfully",
                req.getLab().getName(),
                req.getLab().getDepartment(),
                req.getFromDate(),
                req.getToDate()
        );
    }

    private ToVerifyReturnResponseDTO mapToVerifyReturnDTO(EquipmentRequest req, boolean damagedFlag) {
        List<RequestItem> items = requestItemRepository.findByRequestId(req.getId());

        List<VerifiedReturnItemDTO> itemDtos = items.stream().map(ri -> new VerifiedReturnItemDTO(
                ri.getEquipment().getId(),
                ri.getEquipment().getName(),
                ri.getIssuedQty(),
                damagedFlag,
                ri.getEquipment().getItemType().name()
        )).toList();

        String msg = damagedFlag ? "Return verified with damage reported" : "Return verified successfully";

        return new ToVerifyReturnResponseDTO(
                req.getId(),
                req.getStatus().name(),
                msg,
                damagedFlag,
                itemDtos
        );
    }

    private StudentMyRequestDTO mapToStudentMyRequestDTO(EquipmentRequest req) {
        List<RequestItem> items = requestItemRepository.findByRequestId(req.getId());

        List<StudentMyRequestItemDTO> itemDtos = items.stream().map(ri ->
                new StudentMyRequestItemDTO(
                        ri.getId(),
                        ri.getEquipment().getId(),
                        ri.getEquipment().getName(),
                        ri.getQuantity(),
                        ri.getEquipment().getItemType().name(),
                        ri.getStatus() == null ? null : ri.getStatus().name(),
                        ri.getToWaitReason(),
                        ri.getIssuedQty(),
                        ri.isReturned(),
                        ri.isDamaged()
                )
        ).toList();

        boolean hasReturnable = items.stream()
                .anyMatch(i -> i.getEquipment().getItemType() == ItemType.RETURNABLE);

        boolean canAcceptIssue = req.getStatus() == RequestStatus.ISSUED_PENDING_STUDENT_ACCEPT;
        boolean canReturn = req.getStatus() == RequestStatus.ISSUED_CONFIRMED && hasReturnable;

        return new StudentMyRequestDTO(
                req.getId(),
                req.getStatus().name(),
                req.getPurpose(),
                req.getFromDate(),
                req.getToDate(),
                req.getLab().getName(),
                req.getLecturer().getFullName(),
                itemDtos,
                canAcceptIssue,
                canReturn
        );
    }


    // DTO ENDPOINT METHODS
    @Transactional
    public ToIssueResponseDTO toIssueDTO(String toEmail, Long requestId) {
        EquipmentRequest req = toIssue(toEmail, requestId);
        return mapToIssueResponseDTO(req);
    }

    @Transactional
    public ToIssueResponseDTO toIssueItemDTO(String toEmail, Long requestItemId) {
        EquipmentRequest req = toIssueItem(toEmail, requestItemId);
        return mapToIssueResponseDTO(req);
    }



    @Transactional
    public StudentAcceptanceDTO studentAcceptIssueDTO(String requesterEmail, Long requestId) {
        EquipmentRequest req = studentAcceptIssue(requesterEmail, requestId);
        return mapToStudentAcceptanceDTO(req);
    }

    @Transactional
    public StudentAcceptanceDTO studentAcceptIssueItemDTO(String requesterEmail, Long requestItemId) {
        EquipmentRequest req = studentAcceptIssueItem(requesterEmail, requestItemId);
        return mapToStudentAcceptanceDTO(req);
    }

    @Transactional
    public StudentReturnDTO submitReturnDTO(String requesterEmail, Long requestId) {
        EquipmentRequest req = submitReturn(requesterEmail, requestId);
        return mapToStudentReturnDTO(req);
    }

    @Transactional
    public StudentReturnDTO submitReturnItemDTO(String requesterEmail, Long requestItemId) {
        EquipmentRequest req = submitReturnItem(requesterEmail, requestItemId);
        return mapToStudentReturnDTO(req);
    }

    @Transactional
    public ToVerifyReturnResponseDTO toVerifyReturnDTO(String toEmail, Long requestId, boolean damaged) {
        EquipmentRequest req = toVerifyReturn(toEmail, requestId, damaged);
        return mapToVerifyReturnDTO(req, damaged);
    }

    @Transactional
    public ToVerifyReturnResponseDTO toVerifyReturnItemDTO(String toEmail, Long requestItemId, boolean damaged) {
        EquipmentRequest req = toVerifyReturnItem(toEmail, requestItemId, damaged);
        return mapToVerifyReturnDTO(req, damaged);
    }

    private void ensureToAllowedForLab(User to, Lab lab) {
        if (to.getRole() != Role.TO) {
            throw new IllegalArgumentException("Only TO can perform this action");
        }

        if (lab == null) {
            throw new IllegalArgumentException("Request has no lab");
        }

        if (lab.getDepartment() == null ||
                to.getDepartment() == null ||
                !com.uoj.equipment.util.DepartmentUtil.equalsNormalized(lab.getDepartment(), to.getDepartment())) {
            throw new IllegalArgumentException("TO department mismatch with lab");
        }

        User assignedTo = lab.getTechnicalOfficer();
        if (assignedTo == null || assignedTo.getId() == null) {
            throw new IllegalArgumentException("No TO assigned to this lab. Ask HOD to assign.");
        }

        if (!assignedTo.getId().equals(to.getId())) {
            throw new IllegalArgumentException("You are not the assigned TO for this lab");
        }
    }

    // java
    @Transactional(readOnly = true)
    public List<RequestSummaryDTO> toRequestsForTo(String toEmail) {
        User to = userRepository.findByEmail(toEmail)
                .orElseThrow(() -> new IllegalArgumentException("TO not found"));

        if (to.getRole() != Role.TO) {
            throw new IllegalArgumentException("Only TO can view these requests");
        }

        // Find labs assigned to this TO
        List<Lab> labs = labRepository.findByTechnicalOfficerId(to.getId());
        if (labs.isEmpty()) {
            throw new IllegalArgumentException("No labs assigned to this TO. Ask HOD to assign.");
        }

        List<Long> labIds = labs.stream()
                .map(Lab::getId)
                .toList();

        // Get all equipment requests for those labs
        List<EquipmentRequest> requests =
                equipmentRequestRepository.findByLabIdInOrderByIdDesc(labIds);

        // Map to DTO
        return requests.stream()
                .map(this::mapToRequestSummaryDTO)
                .toList();
    }




}
