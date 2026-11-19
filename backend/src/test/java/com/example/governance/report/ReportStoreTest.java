package com.example.governance.report;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ReportStoreTest {
    @Test void reportsAndDashboardsAreIsolatedByOwner() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate();
        var jdbc = new JdbcTemplate(db); var tx = new DataSourceTransactionManager(db);
        jdbc.update("INSERT INTO departments(id,name) VALUES (1,'dept')");
        jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES (1,'a','hash',1,TRUE,'a'),(2,'b','hash',1,TRUE,'b')");
        jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (1,'db','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher')");
        jdbc.update("INSERT INTO datasets(id,source_id,name,table_name) VALUES (1,1,'dataset','data')");
        var store = new ReportStore(jdbc, tx);
        long report = store.createReport(1, 1, "Sales", "TABLE", "PREVIEW", "{\"fields\":[\"ID\"],\"limit\":100}");
        assertTrue(store.report(1, report).isPresent()); assertTrue(store.report(2, report).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> store.createDashboard(2, "Other", List.of(report)));
        long dashboard = store.createDashboard(1, "Overview", List.of(report));
        assertEquals(List.of(report), store.dashboard(1, dashboard).orElseThrow().reports());
        assertTrue(store.dashboard(2, dashboard).isEmpty());
    }
    @Test void ownerCanEditAndDeleteReportsAndDashboardsWithoutCrossOwnerAccess() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate();
        var jdbc = new JdbcTemplate(db); var tx = new DataSourceTransactionManager(db);
        jdbc.update("INSERT INTO departments(id,name) VALUES (1,'dept')");
        jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES (1,'a','hash',1,TRUE,'a'),(2,'b','hash',1,TRUE,'b')");
        jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (1,'db','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher')");
        jdbc.update("INSERT INTO datasets(id,source_id,name,table_name) VALUES (1,1,'dataset','data')");
        var store = new ReportStore(jdbc, tx);
        long first = store.createReport(1,1,"First","TABLE","PREVIEW","{}");
        long second = store.createReport(1,1,"Second","TABLE","PREVIEW","{}");
        long foreign = store.createReport(2,1,"Foreign","TABLE","PREVIEW","{}");
        long dashboard = store.createDashboard(1,"Dashboard",List.of(first));
        assertFalse(store.updateReport(2, first,1,"Changed","LINE","PREVIEW","{}"));
        assertFalse(store.deleteDashboard(2,dashboard));
        assertThrows(IllegalArgumentException.class, () -> store.updateDashboard(1,dashboard,"Invalid",List.of(foreign)));
        assertEquals(List.of(first),store.dashboard(1,dashboard).orElseThrow().reports());
        assertTrue(store.updateReport(1,first,1,"Changed","LINE","PREVIEW","{}"));
        assertEquals("Changed",store.report(1,first).orElseThrow().name());
        assertTrue(store.updateDashboard(1,dashboard,"Updated",List.of(second,first)));
        assertEquals(List.of(second,first),store.dashboard(1,dashboard).orElseThrow().reports());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> store.deleteReport(1,first));
        assertTrue(store.deleteDashboard(1,dashboard)); assertTrue(store.deleteReport(1,first));
        assertFalse(store.deleteReport(2,second)); assertTrue(store.report(1,second).isPresent());
    }
}
