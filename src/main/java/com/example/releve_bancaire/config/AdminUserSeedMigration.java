package com.example.releve_bancaire.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class AdminUserSeedMigration implements CommandLineRunner {

    @Override
    public void run(String... args) {
        log.info("Admin user seed skipped (lite mode: no auth).");
    }
}
