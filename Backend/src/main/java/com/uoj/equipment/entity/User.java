package com.uoj.equipment.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.uoj.equipment.enums.Role;
import jakarta.persistence.*;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 120)
    private String email;

    @JsonIgnore
    @Column(name = "password_hash", nullable = false, length = 120)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Role role;

    @Column(nullable = false, length = 20)
    private String department;

    @Column(nullable = false)
    private boolean enabled = true;

    // NEW: email must be verified before login is allowed (except admin-created users)
    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    // Student only
    @Column(name = "reg_no", unique = true, length = 40)
    private String regNo;

    @Column(name = "full_name", length = 120)
    private String fullName;

    public User() {}

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public String getDepartment() { return department; }
    public boolean isEnabled() { return enabled; }
    public boolean isEmailVerified() { return emailVerified; }
    public String getRegNo() { return regNo; }
    public String getFullName() { return fullName; }

    public void setId(Long id) { this.id = id; }
    public void setEmail(String email) { this.email = email; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public void setRole(Role role) { this.role = role; }
    public void setDepartment(String department) { this.department = department; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setEmailVerified(boolean emailVerified) { this.emailVerified = emailVerified; }
    public void setRegNo(String regNo) { this.regNo = regNo; }
    public void setFullName(String fullName) { this.fullName = fullName; }
}