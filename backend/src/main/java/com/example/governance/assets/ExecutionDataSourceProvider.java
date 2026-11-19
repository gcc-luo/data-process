package com.example.governance.assets;

import com.example.governance.assets.driver.ConnectionFactory;
import com.example.governance.workflow.CamelTaskRunner;
import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** Internal execution identity; never expose this object or decrypted credentials through an API. */
public final class ExecutionDataSourceProvider implements CamelTaskRunner.DataSourceProvider {
    private final DataSourceStore store;
    private final ConnectionFactory factory;
    public ExecutionDataSourceProvider(DataSourceStore store, ConnectionFactory factory) { this.store = store; this.factory = factory; }
    @Override public DataSource get(long sourceId) throws GeneralSecurityException {
        var definition = store.find(sourceId).orElseThrow(() -> new IllegalArgumentException("Unknown data source"));
        String password = store.passwordForExecution(sourceId);
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException { return factory.open(definition, password); }
            @Override public Connection getConnection(String username, String overridePassword) throws SQLException {
                var overridden = new DataSourceDefinition(definition.name(), definition.profileId(), definition.host(), definition.port(), definition.database(), username, definition.driverId(), definition.options());
                return factory.open(overridden, overridePassword);
            }
        };
    }
}
