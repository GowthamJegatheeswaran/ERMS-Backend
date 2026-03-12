package com.uoj.equipment.controller;

import com.uoj.equipment.dto.LabDTO;
import com.uoj.equipment.service.HodLabService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * FIX BUG 1 + BUG 11:
 *   Old controller returned List<Lab> (raw JPA entity). Two problems:
 *   1. Jackson serialized the full nested User entity in lab.technicalOfficer —
 *      exposing internal fields and risking circular reference errors.
 *   2. Lab.technicalOfficer is LAZY — accessing it after the @Transactional
 *      session closed throws LazyInitializationException.
 *
 *   FIX: Both listLabs(), assignTo(), and clearTo() now return LabDTO.
 *   LabDTO contains: id, name, department, technicalOfficerId,
 *                    technicalOfficerName, technicalOfficerEmail
 *   This is exactly what the frontend needs for the dropdown and status display.
 *   The service method signatures changed accordingly.
 */
@RestController
@RequestMapping("/api/hod/labs")
public class HodLabController {

    private final HodLabService hodLabService;

    public HodLabController(HodLabService hodLabService) {
        this.hodLabService = hodLabService;
    }

    /** HOD view all labs in own department with assigned TO. */
    @GetMapping
    public ResponseEntity<List<LabDTO>> listLabs(Authentication auth) {
        return ResponseEntity.ok(hodLabService.listLabsForHod(auth.getName()));
    }

    /** HOD assign a TO to a specific lab. Returns updated LabDTO. */
    @PostMapping("/{labId}/assign-to")
    public ResponseEntity<LabDTO> assignTo(Authentication auth,
                                           @PathVariable Long labId,
                                           @RequestParam Long toUserId) {
        return ResponseEntity.ok(hodLabService.assignToToLab(auth.getName(), labId, toUserId));
    }

    /** HOD clear TO assignment from a lab. Returns updated LabDTO. */
    @PostMapping("/{labId}/clear-to")
    public ResponseEntity<LabDTO> clearTo(Authentication auth,
                                          @PathVariable Long labId) {
        return ResponseEntity.ok(hodLabService.clearToFromLab(auth.getName(), labId));
    }
}