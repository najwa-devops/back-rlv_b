package com.example.releve_bancaire.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class Banking_AsyncConfig {

    @Bean(name = "bankingTaskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1); // One thread for sequential processing
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1000); // Larger queue for multiple uploads
        executor.setThreadNamePrefix("BankingAsync-");
        executor.initialize();
        return executor;
    }
}
