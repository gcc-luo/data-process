package com.example.governance.assets;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionDataSourceProviderTest {
    @Test void resolvesStoredConnectionAndDoesNotLogOrReturnPasswordAsMetadata() throws Exception {
        var store = mock(DataSourceStore.class);
        var definition = new DataSourceDefinition("prod", DataSourceDefinition.Type.POSTGRESQL, "db.example", 5432, "sales", "reader");
        when(store.find(4)).thenReturn(java.util.Optional.of(definition));
        when(store.passwordForExecution(4)).thenReturn("secret");
        var provider = new ExecutionDataSourceProvider(store);
        var source = (DriverManagerDataSource) provider.get(4);
        assertEquals("jdbc:postgresql://db.example:5432/sales", source.getUrl());
        assertEquals("reader", source.getUsername());
        assertFalse(definition.toString().contains("secret"));
    }
    @Test void rejectsUnknownDataSource() {
        var store = mock(DataSourceStore.class);
        when(store.find(4)).thenReturn(java.util.Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> new ExecutionDataSourceProvider(store).get(4));
    }
}
