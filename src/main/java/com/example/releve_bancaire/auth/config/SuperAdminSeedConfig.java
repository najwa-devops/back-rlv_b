package com.example.releve_bancaire.auth.config;

import com.example.releve_bancaire.auth.entity.AppRole;
import com.example.releve_bancaire.auth.entity.AppUser;
import com.example.releve_bancaire.auth.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@Profile("auth")
@RequiredArgsConstructor
@Slf4j
public class SuperAdminSeedConfig {

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;

    @Bean
    ApplicationRunner seedSuperAdmin(
            @Value("${security.seed.superadmin.enabled:true}") boolean enabled,
            @Value("${security.seed.superadmin.email:superadmin@invoice.local}") String email,
            @Value("${security.seed.superadmin.password:Admin@123}") String password) {
        return args -> {
            if (!enabled) {
                return;
            }

            AppUser user = appUserRepository.findByEmailIgnoreCase(email)
                    .orElseGet(() -> AppUser.builder()
                            .email(email)
                            .name("Super Admin")
                            .role(AppRole.SUPER_ADMIN)
                            .active(true)
                            .build());

            user.setName(user.getName() == null || user.getName().isBlank() ? "Super Admin" : user.getName());
            user.setRole(AppRole.SUPER_ADMIN);
            user.setActive(true);
            user.setPasswordHash(passwordEncoder.encode(password));

            AppUser saved = appUserRepository.save(user);
            log.info("Super-admin prêt: {} (id={})", saved.getEmail(), saved.getId());
        };
    }
}

