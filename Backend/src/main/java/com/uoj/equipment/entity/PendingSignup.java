package com.uoj.equipment.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Stores a signup request temporarily while waiting for OTP verification.
 * Once the OTP is confirmed, the real User record is created and this row is deleted.
 */
@Entity
@Table(name = "pending_signups")
public class PendingSignup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 120)
    private String email;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Column(name = "reg_no", nullable = false, unique = true, length = 40)
    private String regNo;

    @Column(nullable = false, length = 20)
    private String department;

    @Column(name = "password_hash", nullable = false, length = 120)
    private String passwordHash;

    @Column(nullable = false, length = 6)
    private String otp;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    public PendingSignup() {}

    public PendingSignup(String email, String fullName, String regNo,
                         String department, String passwordHash,
                         String otp, LocalDateTime expiresAt) {
        this.email        = email;
        this.fullName     = fullName;
        this.regNo        = regNo;
        this.department   = department;
        this.passwordHash = passwordHash;
        this.otp          = otp;
        this.expiresAt    = expiresAt;
    }

    public Long          getId()                         { return id; }
    public String        getEmail()                      { return email; }
    public void          setEmail(String email)          { this.email = email; }
    public String        getFullName()                   { return fullName; }
    public void          setFullName(String fullName)    { this.fullName = fullName; }
    public String        getRegNo()                      { return regNo; }
    public void          setRegNo(String regNo)          { this.regNo = regNo; }
    public String        getDepartment()                 { return department; }
    public void          setDepartment(String dept)      { this.department = dept; }
    public String        getPasswordHash()               { return passwordHash; }
    public void          setPasswordHash(String h)       { this.passwordHash = h; }
    public String        getOtp()                        { return otp; }
    public void          setOtp(String otp)              { this.otp = otp; }
    public LocalDateTime getExpiresAt()                  { return expiresAt; }
    public void          setExpiresAt(LocalDateTime e)   { this.expiresAt = e; }
}