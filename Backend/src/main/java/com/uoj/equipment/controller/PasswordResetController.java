package com.uoj.equipment.controller;

import com.uoj.equipment.dto.ForgotPasswordRequestDTO;
import com.uoj.equipment.dto.ResetPasswordDTO;
import com.uoj.equipment.service.PasswordResetService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/password")
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    @Value("${app.frontend.base-url:https://equipment-request-frontend-production.up.railway.app}")
    private String frontendBaseUrl;

    public PasswordResetController(PasswordResetService passwordResetService) {
        this.passwordResetService = passwordResetService;
    }

//    @PostMapping("/forgot")
//    public Map<String, Object> forgot(@RequestBody ForgotPasswordRequestDTO dto) {
//        return passwordResetService.forgotPassword(dto, frontendBaseUrl);
//    }
//
//    @PostMapping("/reset")
//    public Map<String, Object> reset(@RequestBody ResetPasswordDTO dto) {
//        return passwordResetService.resetPassword(dto);
//    }
}
