package com.uoj.equipment.dto;

public class SimpleUserDTO {
    private Long id;
    private String fullName;
    private String email;
    private String regNo;
    private String department;
    private String role;
    private boolean enabled;

    // Backward-compatible constructor (older code uses 4 args)
    public SimpleUserDTO(Long id, String fullName, String email, String role) {
        this(id, fullName, email, null, null, role, true);
    }

    public SimpleUserDTO(Long id, String fullName, String email, String regNo, String department, String role) {
        this(id, fullName, email, regNo, department, role, true);
    }

    public SimpleUserDTO(Long id, String fullName, String email, String regNo, String department, String role, boolean enabled) {
        this.id = id;
        this.fullName = fullName;
        this.email = email;
        this.regNo = regNo;
        this.department = department;
        this.role = role;
        this.enabled = enabled;
    }

    public Long getId() { return id; }
    public String getFullName() { return fullName; }
    public String getEmail() { return email; }
    public String getRegNo() { return regNo; }
    public String getDepartment() { return department; }
    public String getRole() { return role; }
    public boolean isEnabled() { return enabled; }

    public void setId(Long id) { this.id = id; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public void setEmail(String email) { this.email = email; }
    public void setRegNo(String regNo) { this.regNo = regNo; }
    public void setDepartment(String department) { this.department = department; }
    public void setRole(String role) { this.role = role; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
