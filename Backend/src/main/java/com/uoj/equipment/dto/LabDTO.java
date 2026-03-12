package com.uoj.equipment.dto;

/**
 * FIX BUG 1:
 *   Extended LabDTO to include technicalOfficerName and technicalOfficerEmail.
 *   The old LabDTO only had technicalOfficerId which meant the frontend couldn't
 *   show the TO's name without a second API call.
 *
 *   Now HodLabController returns this DTO instead of the raw Lab entity,
 *   which prevents LazyInitializationException and stops internal User fields
 *   from being exposed to the frontend.
 */
public class LabDTO {
    private Long id;
    private String name;
    private String department;

    // Assigned Technical Officer info (all null if no TO assigned)
    private Long technicalOfficerId;
    private String technicalOfficerName;
    private String technicalOfficerEmail;

    public LabDTO() {}

    // Legacy constructor (used by CommonLookupController — backwards compatible)
    public LabDTO(Long id, String name, String department, Long technicalOfficerId) {
        this.id = id;
        this.name = name;
        this.department = department;
        this.technicalOfficerId = technicalOfficerId;
    }

    // Full constructor
    public LabDTO(Long id, String name, String department,
                  Long technicalOfficerId, String technicalOfficerName, String technicalOfficerEmail) {
        this.id = id;
        this.name = name;
        this.department = department;
        this.technicalOfficerId = technicalOfficerId;
        this.technicalOfficerName = technicalOfficerName;
        this.technicalOfficerEmail = technicalOfficerEmail;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDepartment() { return department; }
    public Long getTechnicalOfficerId() { return technicalOfficerId; }
    public String getTechnicalOfficerName() { return technicalOfficerName; }
    public String getTechnicalOfficerEmail() { return technicalOfficerEmail; }

    public void setId(Long id) { this.id = id; }
    public void setName(String name) { this.name = name; }
    public void setDepartment(String department) { this.department = department; }
    public void setTechnicalOfficerId(Long technicalOfficerId) { this.technicalOfficerId = technicalOfficerId; }
    public void setTechnicalOfficerName(String technicalOfficerName) { this.technicalOfficerName = technicalOfficerName; }
    public void setTechnicalOfficerEmail(String technicalOfficerEmail) { this.technicalOfficerEmail = technicalOfficerEmail; }
}