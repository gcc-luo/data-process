package com.example.governance.assets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class DataSourceConfiguration {
    @Bean CredentialCipher credentialCipher(@Value("${governance.credential-key}") String key) { return new CredentialCipher(key); }
    @Bean DatabaseInspector databaseInspector() { return new DatabaseInspector(); }
    @Bean DatasetStore datasetStore(JdbcTemplate jdbc, PlatformTransactionManager manager) { return new DatasetStore(jdbc, manager); }
    @Bean DataSourceStore dataSourceStore(JdbcTemplate jdbc, CredentialCipher cipher) { return new DataSourceStore(jdbc, cipher); }
}
