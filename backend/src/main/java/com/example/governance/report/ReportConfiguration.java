package com.example.governance.report;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class ReportConfiguration {
    @Bean ReportStore reportStore(JdbcTemplate jdbc, PlatformTransactionManager manager) { return new ReportStore(jdbc, manager); }
}
