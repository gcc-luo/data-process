package com.example.governance.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseSyncExecutorTest {
    @Test void copiesRowsAndRollsBackTargetWhenWriteFails() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var target = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (var c = source.getConnection(); var s = c.createStatement()) { s.execute("CREATE TABLE src(id INT, name VARCHAR(40))"); s.execute("INSERT INTO src VALUES (1,'A'), (2,'B')"); }
        try (var c = target.getConnection(); var s = c.createStatement()) { s.execute("CREATE TABLE dst(id INT PRIMARY KEY, name VARCHAR(40))"); }
        try (var executor = new DatabaseSyncExecutor()) {
            assertEquals(2, executor.append(source, target, "SELECT id, name FROM src ORDER BY id", "dst", List.of("id", "name"), 1));
            assertThrows(Exception.class, () -> executor.append(source, target, "SELECT id, name FROM src ORDER BY id", "dst", List.of("id", "name"), 1));
        }
        try (var c = target.getConnection(); var s = c.createStatement(); var rows = s.executeQuery("SELECT COUNT(*) FROM dst")) {
            assertTrue(rows.next()); assertEquals(2, rows.getInt(1));
        }
    }
    @Test void rollsBackWhenCancelledDuringFinalBatch() throws Exception {
        String srcUrl = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        String dstUrl = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        var source = new DriverManagerDataSource(srcUrl, "sa", "");
        try (var c = source.getConnection(); var s = c.createStatement()) { s.execute("CREATE TABLE src(id INT)"); s.execute("INSERT INTO src VALUES (1)"); }
        try (var c = new DriverManagerDataSource(dstUrl, "sa", "").getConnection(); var s = c.createStatement()) { s.execute("CREATE TABLE dst(id INT PRIMARY KEY)"); }
        var cancel = new RunCancelContext();
        // 目标连接的 executeBatch 真实执行后置取消标志，模拟“末批写完”与 stop 的竞态
        var target = new DriverManagerDataSource(dstUrl, "sa", "") {
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                java.sql.Connection real = super.getConnection();
                return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{java.sql.Connection.class}, (p, m, a) -> {
                    try {
                        Object result = m.invoke(real, a);
                        if (m.getName().equals("prepareStatement")) {
                            java.sql.PreparedStatement ps = (java.sql.PreparedStatement) result;
                            return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{java.sql.PreparedStatement.class}, (p2, m2, a2) -> {
                                try { Object r2 = m2.invoke(ps, a2); if (m2.getName().equals("executeBatch")) cancel.cancel(); return r2; }
                                catch (java.lang.reflect.InvocationTargetException ex) { throw ex.getCause(); }
                            });
                        }
                        return result;
                    } catch (java.lang.reflect.InvocationTargetException ex) { throw ex.getCause(); }
                });
            }
        };
        try (var executor = new DatabaseSyncExecutor()) {
            assertThrows(Exception.class, () -> executor.append(source, target, "SELECT id FROM src", List.of(), "dst", List.of("id"), 1, cancel));
        }
        try (var c = new DriverManagerDataSource(dstUrl, "sa", "").getConnection(); var s = c.createStatement(); var rows = s.executeQuery("SELECT COUNT(*) FROM dst")) {
            assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
        }
    }
    @Test void refusesUnsafeIdentifiers() throws Exception {
        try (var executor = new DatabaseSyncExecutor()) {
            var db = new DriverManagerDataSource("jdbc:h2:mem:test", "sa", "");
            assertThrows(IllegalArgumentException.class, () -> executor.append(db, db, "SELECT 1", "dst; DROP TABLE users", List.of("id"), 1));
        }
    }
    @Test void rollsBackEarlierBatchesWhenLaterBatchFails() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var target = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (var c = source.getConnection(); var s = c.createStatement()) { s.execute("CREATE TABLE src(id INT)"); s.execute("INSERT INTO src VALUES (1), (2)"); }
        try (var c = target.getConnection(); var s = c.createStatement()) { s.execute("CREATE TABLE dst(id INT PRIMARY KEY)"); s.execute("INSERT INTO dst VALUES (2)"); }
        try (var executor = new DatabaseSyncExecutor()) {
            assertThrows(Exception.class, () -> executor.append(source, target, "SELECT id FROM src ORDER BY id", "dst", List.of("id"), 1));
        }
        try (var c = target.getConnection(); var s = c.createStatement(); var rows = s.executeQuery("SELECT id FROM dst")) {
            assertTrue(rows.next()); assertEquals(2, rows.getInt(1)); assertFalse(rows.next());
        }
    }
    @Test void upsertUsesPrimaryKeyAndRejectsNonKeyConfiguration() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var target = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (var c = source.getConnection(); var st = c.createStatement()) { st.execute("CREATE TABLE src(id INT, name VARCHAR(40))"); st.execute("INSERT INTO src VALUES (1,'new'),(2,'second')"); }
        try (var c = target.getConnection(); var st = c.createStatement()) { st.execute("CREATE TABLE dst(id INT PRIMARY KEY, name VARCHAR(40))"); st.execute("INSERT INTO dst VALUES (1,'old')"); }
        try (var executor = new DatabaseSyncExecutor()) {
            assertEquals(2, executor.upsert(source, target, "SELECT id,name FROM src ORDER BY id", List.of(), "dst", List.of("id","name"), List.of("id"), 100));
            assertThrows(Exception.class, () -> executor.upsert(source, target, "SELECT id,name FROM src", List.of(), "dst", List.of("id","name"), List.of("name"), 100));
        }
        try (var c = target.getConnection(); var st = c.createStatement(); var rows = st.executeQuery("SELECT name FROM dst WHERE id=1")) {
            assertTrue(rows.next()); assertEquals("new", rows.getString(1));
        }
    }
    @Test void supportsADeclaredUniqueKeyThatIsNotThePrimaryKey() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var target = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (var c = source.getConnection(); var st = c.createStatement()) { st.execute("CREATE TABLE src(code VARCHAR(20), name VARCHAR(40))"); st.execute("INSERT INTO src VALUES ('x','new'),('y','second')"); }
        try (var c = target.getConnection(); var st = c.createStatement()) { st.execute("CREATE TABLE dst(id INT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, code VARCHAR(20) UNIQUE, name VARCHAR(40))"); st.execute("INSERT INTO dst(code,name) VALUES ('x','old')"); }
        try (var executor = new DatabaseSyncExecutor()) {
            assertEquals(2, executor.upsert(source, target, "SELECT code,name FROM src ORDER BY code", List.of(), "dst", List.of("code","name"), List.of("code"), 10));
        }
        try (var c = target.getConnection(); var st = c.createStatement(); var rows = st.executeQuery("SELECT name FROM dst WHERE code='x'")) { assertTrue(rows.next()); assertEquals("new", rows.getString(1)); }
    }
}
