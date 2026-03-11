package com.uoj.equipment.service;

import com.uoj.equipment.dto.NotificationDTO;
import com.uoj.equipment.entity.Notification;
import com.uoj.equipment.entity.User;
import com.uoj.equipment.enums.NotificationType;
import com.uoj.equipment.repository.NotificationRepository;
import com.uoj.equipment.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;

    public NotificationService(NotificationRepository notificationRepository,
                               UserRepository userRepository,
                               EmailService emailService) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.emailService = emailService;
    }

    /**
     * Core method: saves in-app notification AND sends email to recipient.
     */
    public void notifyUser(User user,
                           NotificationType type,
                           String title,
                           String message,
                           Long relatedRequestId,
                           Long relatedPurchaseId) {

        // 1. Save in-app notification
        Notification n = new Notification();
        n.setUser(user);
        n.setType(type);
        n.setTitle(title);
        n.setMessage(message);
        n.setRelatedRequestId(relatedRequestId);
        n.setRelatedPurchaseId(relatedPurchaseId);
        n.setCreatedDate(LocalDateTime.now());
        n.setReadFlag(false);
        notificationRepository.save(n);

        // 2. Send email notification (non-blocking — log failure but don't crash)
        try {
            String emailBody = buildEmailBody(user.getFullName(), title, message, relatedRequestId, relatedPurchaseId);
            emailService.sendPlainTextEmail(user.getEmail(), "[ERMS] " + title, emailBody);
        } catch (Exception e) {
            // Email failure should never block the main workflow
            System.err.println("[NotificationService] Email send failed for " + user.getEmail() + ": " + e.getMessage());
        }
    }

    // ─── Workflow-specific helper methods with rich messages ──────────────────

    /** Student/Staff submitted a new request — notify the assigned lecturer */
    public void notifyLecturerNewRequest(User lecturer, User requester, Long requestId, String labName) {
        String role = requester.getRole().name();
        String displayRole = role.equals("STAFF") ? "Staff" : "Student";
        notifyUser(
            lecturer,
            NotificationType.REQUEST_SUBMITTED,
            "New Equipment Request Pending Your Approval",
            displayRole + " " + requester.getFullName() + " (" + (requester.getRegNo() != null ? requester.getRegNo() : requester.getEmail()) + ") " +
            "has submitted an equipment request for lab \"" + labName + "\" (Request #" + requestId + "). " +
            "Please review and approve or reject the request.",
            requestId, null
        );
    }

    /** Requester notified their request was approved by lecturer */
    public void notifyRequesterApproved(User requester, Long requestId, String lecturerName) {
        notifyUser(
            requester,
            NotificationType.REQUEST_APPROVED,
            "Your Request Has Been Approved",
            "Good news! Lecturer " + lecturerName + " has approved your equipment request #" + requestId + ". " +
            "Your request is now being processed by the Technical Officer. You will be notified when the equipment is ready for collection.",
            requestId, null
        );
    }

    /** Requester notified their request was rejected by lecturer */
    public void notifyRequesterRejected(User requester, Long requestId, String lecturerName, String reason) {
        String reasonText = (reason != null && !reason.isBlank()) ? " Reason: " + reason : "";
        notifyUser(
            requester,
            NotificationType.REQUEST_REJECTED,
            "Your Request Has Been Rejected",
            "Your equipment request #" + requestId + " was rejected by Lecturer " + lecturerName + "." + reasonText +
            " You may submit a new request if needed.",
            requestId, null
        );
    }

    /** TO notified a request in their lab is ready to process */
    public void notifyToRequestReadyToIssue(User to, User requester, Long requestId, String labName) {
        notifyUser(
            to,
            NotificationType.REQUEST_APPROVED,
            "New Request Ready for Issuance",
            "A lecturer-approved equipment request (#" + requestId + ") from " + requester.getFullName() +
            " is now ready for issuance at lab \"" + labName + "\". Please issue the equipment at your earliest convenience.",
            requestId, null
        );
    }

    /** Requester notified equipment has been issued — waiting for them to accept */
    public void notifyRequesterIssueReady(User requester, Long requestId, String labName, String toName) {
        notifyUser(
            requester,
            NotificationType.ISSUE_READY,
            "Equipment Ready for Collection",
            "Your equipment for request #" + requestId + " has been issued by Technical Officer " + toName +
            " at lab \"" + labName + "\". Please collect the equipment and confirm receipt in the system.",
            requestId, null
        );
    }

    /** TO notified requester has confirmed receipt */
    public void notifyToIssueAccepted(User to, User requester, Long requestId) {
        notifyUser(
            to,
            NotificationType.ISSUE_ACCEPTED,
            "Equipment Receipt Confirmed",
            requester.getFullName() + " has confirmed receipt of equipment for request #" + requestId + ".",
            requestId, null
        );
    }

    /** TO notified a return has been submitted */
    public void notifyToReturnSubmitted(User to, User requester, Long requestId, String labName) {
        notifyUser(
            to,
            NotificationType.RETURN_SUBMITTED,
            "Equipment Return Submitted",
            requester.getFullName() + " has submitted a return for request #" + requestId +
            " (Lab: " + labName + "). Please inspect and verify the returned equipment.",
            requestId, null
        );
    }

    /** Requester notified their return was verified */
    public void notifyRequesterReturnVerified(User requester, Long requestId) {
        notifyUser(
            requester,
            NotificationType.RETURN_VERIFIED,
            "Return Successfully Verified",
            "The Technical Officer has verified the return of equipment for your request #" + requestId + ". " +
            "The transaction is now complete. Thank you!",
            requestId, null
        );
    }

    /** Requester notified damage was reported on their return */
    public void notifyRequesterDamageReported(User requester, Long requestId) {
        notifyUser(
            requester,
            NotificationType.DAMAGE_REPORTED,
            "Damage Reported on Returned Equipment",
            "The Technical Officer has reported damage on the equipment returned for request #" + requestId + ". " +
            "Please contact the lab or your department for further instructions.",
            requestId, null
        );
    }

    /** Requester notified TO has put their item on wait */
    public void notifyRequesterToWait(User requester, Long requestId, String reason) {
        String reasonText = (reason != null && !reason.isBlank()) ? " Reason: " + reason : " No reason provided.";
        notifyUser(
            requester,
            NotificationType.TO_WAIT,
            "Equipment Temporarily Unavailable",
            "The Technical Officer has placed your equipment request #" + requestId + " on hold." + reasonText +
            " You will be notified when the equipment becomes available.",
            requestId, null
        );
    }

    // ─── Purchase workflow notifications ──────────────────────────────────────

    /** HOD notified TO submitted a purchase request */
    public void notifyHodPurchaseSubmitted(User hod, User to, Long purchaseId, String department) {
        notifyUser(
            hod,
            NotificationType.PURCHASE_SUBMITTED,
            "New Purchase Request Awaiting Your Approval",
            "Technical Officer " + to.getFullName() + " has submitted a purchase request (#" + purchaseId + ") " +
            "for the " + department + " department. Please review and approve or reject.",
            null, purchaseId
        );
    }

    /** TO notified their purchase was approved by HOD */
    public void notifyToPurchaseApprovedByHod(User to, Long purchaseId, String hodName) {
        notifyUser(
            to,
            NotificationType.PURCHASE_APPROVED_BY_HOD,
            "Purchase Request Approved by HOD",
            "HOD " + hodName + " has approved your purchase request #" + purchaseId + ". " +
            "The request has been forwarded to the Admin for final approval.",
            null, purchaseId
        );
    }

    /** TO notified their purchase was rejected by HOD */
    public void notifyToPurchaseRejectedByHod(User to, Long purchaseId, String hodName, String comment) {
        String commentText = (comment != null && !comment.isBlank()) ? " Comment: " + comment : "";
        notifyUser(
            to,
            NotificationType.PURCHASE_REJECTED_BY_HOD,
            "Purchase Request Rejected by HOD",
            "HOD " + hodName + " has rejected your purchase request #" + purchaseId + "." + commentText,
            null, purchaseId
        );
    }

    /** HOD notified Admin issued/approved the purchase */
    public void notifyHodPurchaseApprovedByAdmin(User hod, Long purchaseId, String issuedDate) {
        notifyUser(
            hod,
            NotificationType.PURCHASE_APPROVED_BY_ADMIN,
            "Purchase Request Issued by Admin",
            "The Admin has approved and issued purchase request #" + purchaseId +
            (issuedDate != null ? " with issued date: " + issuedDate : "") + ". " +
            "Please confirm receipt of the items once they arrive.",
            null, purchaseId
        );
    }

    /** HOD notified Admin rejected the purchase */
    public void notifyHodPurchaseRejectedByAdmin(User hod, Long purchaseId, String reason) {
        String reasonText = (reason != null && !reason.isBlank()) ? " Reason: " + reason : "";
        notifyUser(
            hod,
            NotificationType.PURCHASE_REJECTED_BY_ADMIN,
            "Purchase Request Rejected by Admin",
            "The Admin has rejected purchase request #" + purchaseId + " for your department." + reasonText,
            null, purchaseId
        );
    }

    // ─── Read / List ──────────────────────────────────────────────────────────

    public List<NotificationDTO> getMyNotifications(String email) {
        User user = userRepository.findByEmail(email).orElseThrow();
        return notificationRepository.findByUserOrderByCreatedDateDesc(user)
                .stream()
                .map(n -> new NotificationDTO(
                        n.getId(),
                        n.getType(),
                        n.getTitle(),
                        n.getMessage(),
                        n.getRelatedRequestId(),
                        n.getRelatedPurchaseId(),
                        n.getCreatedDate(),
                        n.isReadFlag()
                ))
                .toList();
    }

    public void markAsRead(String email, Long notificationId) {
        Notification n = notificationRepository.findById(notificationId).orElseThrow();
        if (!n.getUser().getEmail().equals(email)) {
            throw new IllegalArgumentException("Not your notification");
        }
        n.setReadFlag(true);
        notificationRepository.save(n);
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private String buildEmailBody(String recipientName, String title, String message,
                                   Long requestId, Long purchaseId) {
        StringBuilder sb = new StringBuilder();
        sb.append("Dear ").append(recipientName != null ? recipientName : "User").append(",\n\n");
        sb.append(message).append("\n\n");
        if (requestId != null) sb.append("Request ID: #").append(requestId).append("\n");
        if (purchaseId != null) sb.append("Purchase ID: #").append(purchaseId).append("\n");
        sb.append("\nPlease log in to the Equipment Request Management System to take action.\n\n");
        sb.append("──────────────────────────────────────\n");
        sb.append("Faculty of Engineering | University of Jaffna\n");
        sb.append("Equipment Request Management System (ERMS)\n");
        sb.append("This is an automated notification. Please do not reply to this email.\n");
        return sb.toString();
    }
}