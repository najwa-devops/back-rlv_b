package com.example.releve_bancaire;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.context.annotation.ComponentScan;

/**
 * Main application entrypoint.
 * Runs the full project with security auto-configuration disabled.
 */
@SpringBootApplication(
        exclude = {
                SecurityAutoConfiguration.class,
                ManagementWebSecurityAutoConfiguration.class
        })
@ComponentScan(basePackages = "com.example.releve_bancaire")
@EntityScan(basePackages = "com.example.releve_bancaire")
@EnableJpaRepositories(basePackages = "com.example.releve_bancaire")
public class ReleveBancaireApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReleveBancaireApplication.class, args);
    }
}
