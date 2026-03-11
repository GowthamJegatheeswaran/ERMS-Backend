package com.uoj.equipment.repository;

import com.uoj.equipment.entity.EquipmentRequest;
import com.uoj.equipment.enums.RequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EquipmentRequestRepository extends JpaRepository<EquipmentRequest, Long> {

    List<EquipmentRequest> findByRequesterIdOrderByIdDesc(Long requesterId);

    // Used for delete safety check — find ALL requests where this user was the approving lecturer
    List<EquipmentRequest> findByLecturerId(Long lecturerId);

    List<EquipmentRequest> findByLecturerIdAndStatusOrderByIdDesc(Long lecturerId, RequestStatus status);

    List<EquipmentRequest> findByLabIdAndStatusOrderByIdDesc(Long labId, RequestStatus status);

    List<EquipmentRequest> findByLabIdInOrderByIdDesc(List<Long> labIds);
}