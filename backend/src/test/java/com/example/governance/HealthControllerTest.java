package com.example.governance;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HealthControllerTest {
    @Test void reportsUnavailableWhenDatabaseCannotBeReached() throws Exception {
        DataSource source = mock(DataSource.class);
        when(source.getConnection()).thenThrow(new SQLException("offline"));
        var result = new HealthController(source).health();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, result.getStatusCode());
        assertEquals("DOWN", result.getBody().get("status"));
    }

    @Test void reportsUpOnlyForAValidConnection() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);
        assertEquals(HttpStatus.OK, new HealthController(source).health().getStatusCode());
        verify(connection).close();
    }
}
