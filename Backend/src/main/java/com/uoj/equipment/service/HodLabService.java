package com.uoj.equipment.service;

import com.uoj.equipment.dto.LabDTO;
import com.uoj.equipment.entity.Lab;
import com.uoj.equipment.entity.User;
import com.uoj.equipment.enums.Role;
import com.uoj.equipment.repository.LabRepository;
import com.uoj.equipment.repository.UserRepository;
import com.uoj.equipment.util.DepartmentUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class HodLabService {

    private final UserRepository userRepository;
    private final LabRepository labRepository;

    public HodLabService(UserRepository userRepository,
                         LabRepository labRepository) {
        this.userRepository = userRepository;
        this.labRepository = labRepository;
    }

    /**
     * FIX BUG 1 + BUG 11:
     *   Now returns List<LabDTO> instead of List<Lab> (raw entity).
     *   - Prevents LazyInitializationException on technicalOfficer
     *   - Prevents exposing internal User entity fields to frontend
     *   - Provides clean { id, name, department, technicalOfficerId,
     *     technicalOfficerName, technicalOfficerEmail } shape to frontend
     */
    @Transactional(readOnly = true)
    public List<LabDTO> listLabsForHod(String hodEmail) {
        User hod = userRepository.findByEmail(hodEmail)
                .orElseThrow(() -> new IllegalArgumentException("HOD not found"));

        if (hod.getRole() != Role.HOD) {
            throw new IllegalArgumentException("Only HOD can view department labs");
        }

        return labRepository.findByDepartmentOrderByIdAsc(hod.getDepartment())
                .stream()
                .map(this::toLabDTO)
                .toList();
    }

    /**
     * FIX BUG 5:
     *   Old code used equalsIgnoreCase() for department comparison.
     *   This fails when admin stores "COM" for a CE department lab/user
     *   (DepartmentUtil treats "COM" == "CE" but raw equalsIgnoreCase does not).
     *
     *   FIX: All department comparisons now use DepartmentUtil.equalsNormalized()
     *   which correctly treats CE / COM / CSE as equivalent.
     */
    @Transactional
    public LabDTO assignToToLab(String hodEmail, Long labId, Long toUserId) {
        User hod = userRepository.findByEmail(hodEmail)
                .orElseThrow(() -> new IllegalArgumentException("HOD not found"));

        if (hod.getRole() != Role.HOD) {
            throw new IllegalArgumentException("Only HOD can assign TO to labs");
        }

        Lab lab = labRepository.findById(labId)
                .orElseThrow(() -> new IllegalArgumentException("Lab not found"));

        // FIX: use equalsNormalized (handles CE/COM/CSE aliases)
        if (!DepartmentUtil.equalsNormalized(lab.getDepartment(), hod.getDepartment())) {
            throw new IllegalArgumentException("Cannot assign TO for lab in another department");
        }

        User toUser = userRepository.findById(toUserId)
                .orElseThrow(() -> new IllegalArgumentException("TO user not found"));

        if (toUser.getRole() != Role.TO) {
            throw new IllegalArgumentException("Selected user is not a TO");
        }

        // FIX: use equalsNormalized (handles CE/COM/CSE aliases)
        if (!DepartmentUtil.equalsNormalized(toUser.getDepartment(), hod.getDepartment())) {
            throw new IllegalArgumentException(
                    "TO department (" + toUser.getDepartment() + ") does not match " +
                    "HOD department (" + hod.getDepartment() + ")");
        }

        lab.setTechnicalOfficer(toUser);
        Lab saved = labRepository.save(lab);
        return toLabDTO(saved);
    }

    @Transactional
    public LabDTO clearToFromLab(String hodEmail, Long labId) {
        User hod = userRepository.findByEmail(hodEmail)
                .orElseThrow(() -> new IllegalArgumentException("HOD not found"));

        if (hod.getRole() != Role.HOD) {
            throw new IllegalArgumentException("Only HOD can clear TO assignment");
        }

        Lab lab = labRepository.findById(labId)
                .orElseThrow(() -> new IllegalArgumentException("Lab not found"));

        // FIX: use equalsNormalized
        if (!DepartmentUtil.equalsNormalized(lab.getDepartment(), hod.getDepartment())) {
            throw new IllegalArgumentException("Cannot clear TO for lab in another department");
        }

        lab.setTechnicalOfficer(null);
        Lab saved = labRepository.save(lab);
        return toLabDTO(saved);
    }

    // ─── DTO mapper ───────────────────────────────────────────────────────────

    private LabDTO toLabDTO(Lab lab) {
        User to = lab.getTechnicalOfficer();
        LabDTO dto = new LabDTO();
        dto.setId(lab.getId());
        dto.setName(lab.getName());
        dto.setDepartment(lab.getDepartment());
        if (to != null) {
            dto.setTechnicalOfficerId(to.getId());
            dto.setTechnicalOfficerName(to.getFullName());
            dto.setTechnicalOfficerEmail(to.getEmail());
        }
        return dto;
    }
}