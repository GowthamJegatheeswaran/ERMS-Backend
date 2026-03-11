package com.uoj.equipment.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.uoj.equipment.entity.User;
import com.uoj.equipment.enums.Role;
import com.uoj.equipment.repository.UserRepository;

@Configuration
public class AdminSeeder {

    @Bean
    CommandLineRunner seedAdmin(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        return args -> {
            String adminEmail = "admin@eng.jfn.ac.lk";

            if (userRepository.existsByEmail(adminEmail)) return;

            User admin = new User();
            admin.setFullName("System Admin");
            admin.setEmail(adminEmail);
            admin.setRegNo("ADMIN");          // any non-null value if DB requires
            admin.setDepartment("ADMIN");     // any non-null value if DB requir
            admin.setRole(Role.ADMIN);
            admin.setEnabled(true);

            // set initial password//
            admin.setPasswordHash(passwordEncoder.encode("Admin@123"));

            userRepository.save(admin);

            System.out.println("✅ Seeded ADMIN: " + adminEmail + " / Admin@123");
        };
    }
}