package com.example.releve_bancaire;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;

/**
 * Main application entrypoint.
 * Runs the full project with security auto-configuration disabled.
 */
@SpringBootApplication(
        scanBasePackages = "com.example.releve_bancaire",
        exclude = {
                SecurityAutoConfiguration.class,
                ManagementWebSecurityAutoConfiguration.class
        })
public class ReleveBancaireApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReleveBancaireApplication.class, args);
    }
}
