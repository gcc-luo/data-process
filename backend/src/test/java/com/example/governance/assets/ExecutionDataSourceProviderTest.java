package com.example.governance.assets;

import com.example.governance.assets.driver.ConnectionFactory;
import org.junit.jupiter.api.Test;
import java.sql.Connection;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionDataSourceProviderTest {
    @Test void delegatesConnectionCreationToFactoryWithoutExposingSecrets() throws Exception {
        var store = mock(DataSourceStore.class);
        var factory = mock(ConnectionFactory.class);
        var definition = new DataSourceDefinition("prod", "POSTGRESQL:general", "db.example", 5432, "sales", "reader");
        when(store.find(4)).thenReturn(Optional.of(definition));
        when(store.passwordForExecution(4)).thenReturn("secret");
        var connection = mock(Connection.class);
        when(factory.open(definition, "secret")).thenReturn(connection);
        var provider = new ExecutionDataSourceProvider(store, factory);
        assertSame(connection, provider.get(4).getConnection());
        verify(factory).open(definition, "secret");
        assertFalse(definition.toString().contains("secret"));
    }
    @Test void credentialOverrideUsesGivenUsernameAndPassword() throws Exception {
        var store = mock(DataSourceStore.class);
        var factory = mock(ConnectionFactory.class);
        var definition = new DataSourceDefinition("prod", "MYSQL:8.x", "db.example", 3306, "sales", "reader", 9L, Map.of("useSSL", "true"));
        when(store.find(4)).thenReturn(Optional.of(definition));
        when(store.passwordForExecution(4)).thenReturn("secret");
        var connection = mock(Connection.class);
        var overridden = new DataSourceDefinition("prod", "MYSQL:8.x", "db.example", 3306, "sales", "other", 9L, Map.of("useSSL", "true"));
        when(factory.open(overridden, "override")).thenReturn(connection);
        assertSame(connection, new ExecutionDataSourceProvider(store, factory).get(4).getConnection("other", "override"));
    }
    @Test void rejectsUnknownDataSource() {
        var store = mock(DataSourceStore.class);
        when(store.find(4)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> new ExecutionDataSourceProvider(store, mock(ConnectionFactory.class)).get(4));
    }
}
