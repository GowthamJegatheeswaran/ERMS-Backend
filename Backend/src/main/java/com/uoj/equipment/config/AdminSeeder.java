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
            admin.setDepartment("ADMIN");
            admin.setRole(Role.ADMIN);
            admin.setEnabled(true);
            admin.setPasswordHash(passwordEncoder.encode("Admin@123"));

            /*
             * FIX BUG 3: Must set emailVerified=true for admin.
             * The login endpoint now checks emailVerified for STUDENT/STAFF accounts.
             * Admin is exempt from that check, but setting it to true is correct
             * practice and future-proofs against any role-agnostic checks added later.
             */
            admin.setEmailVerified(true);

            /*
             * FIX BUG 9: Do NOT set regNo on admin.
             * User.regNo has a UNIQUE constraint. Setting regNo='ADMIN' means:
             *   - On a clean database, seeding works fine.
             *   - On re-seed or schema migration, it throws a unique constraint violation
             *     because 'ADMIN' is already taken by the previously seeded row.
             *   - It also prevents any student from registering with regNo='ADMIN'
             *     (unlikely but unnecessarily occupies the value).
             * Leaving regNo as null is safe — MySQL/PostgreSQL allow multiple NULL
             * values in a unique column.
             */

            userRepository.save(admin);

            System.out.println("Seeded ADMIN: " + adminEmail + " / Admin@123");
        };
    }
}