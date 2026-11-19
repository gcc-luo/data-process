package com.example.governance.assets;

import com.example.governance.assets.driver.ConnectionFactory;
import com.example.governance.assets.driver.DatabaseProfile;
import com.example.governance.assets.driver.DatabaseProfileRegistry;
import com.example.governance.assets.driver.DriverStore;
import com.example.governance.assets.driver.IsolatedDriverLoader;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class DataSourceConfiguration {
    @Bean CredentialCipher credentialCipher(@Value("${governance.credential-key}") String key) { return new CredentialCipher(key); }
    @Bean IsolatedDriverLoader isolatedDriverLoader() { return new IsolatedDriverLoader(); }
    @Bean DatabaseProfileRegistry databaseProfileRegistry(List<DatabaseProfile> profiles) { return new DatabaseProfileRegistry(profiles); }
    @Bean DriverStore driverStore(JdbcTemplate jdbc, IsolatedDriverLoader loader, org.flywaydb.core.Flyway flyway, @Value("${governance.driver-dir:./drivers}") String dir) {
        flyway.migrate();
        var store = new DriverStore(jdbc, loader, Path.of(dir));
        store.registerBuiltins();
        return store;
    }
    @Bean ConnectionFactory connectionFactory(DatabaseProfileRegistry registry, DriverStore drivers) { return new ConnectionFactory(registry, drivers); }
    @Bean DatabaseInspector databaseInspector(ConnectionFactory factory) { return new DatabaseInspector(factory); }
    @Bean DatasetStore datasetStore(JdbcTemplate jdbc, PlatformTransactionManager manager) { return new DatasetStore(jdbc, manager); }
    @Bean DataSourceStore dataSourceStore(JdbcTemplate jdbc, CredentialCipher cipher) { return new DataSourceStore(jdbc, cipher); }
}
