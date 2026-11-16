package com.example.governance.serviceapi;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class ServiceConfiguration {
    @Bean ServiceCatalogStore serviceCatalogStore(JdbcTemplate jdbc, PlatformTransactionManager manager) { return new ServiceCatalogStore(jdbc, manager); }
}
