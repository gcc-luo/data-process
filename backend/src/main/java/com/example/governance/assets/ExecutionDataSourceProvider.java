package com.example.governance.assets;

import com.example.governance.workflow.CamelTaskRunner;
import java.security.GeneralSecurityException;
import java.util.Properties;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Internal execution identity; never expose this object or decrypted credentials through an API. */
public final class ExecutionDataSourceProvider implements CamelTaskRunner.DataSourceProvider {
    private final DataSourceStore store;
    public ExecutionDataSourceProvider(DataSourceStore store) { this.store = store; }
    @Override public DataSource get(long sourceId) throws GeneralSecurityException {
        var definition = store.find(sourceId).orElseThrow(() -> new IllegalArgumentException("Unknown data source"));
        var source = new DriverManagerDataSource();
        source.setUrl(definition.jdbcUrl());
        source.setUsername(definition.username());
        source.setPassword(store.passwordForExecution(sourceId));
        var props = new Properties();
        switch (definition.type()) {
            case MYSQL -> { props.setProperty("connectTimeout", "5000"); props.setProperty("socketTimeout", "10000"); }
            case POSTGRESQL -> { props.setProperty("connectTimeout", "5"); props.setProperty("socketTimeout", "10"); }
            case ORACLE -> { props.setProperty("oracle.net.CONNECT_TIMEOUT", "5000"); props.setProperty("oracle.jdbc.ReadTimeout", "10000"); }
        }
        source.setConnectionProperties(props);
        return source;
    }
}
