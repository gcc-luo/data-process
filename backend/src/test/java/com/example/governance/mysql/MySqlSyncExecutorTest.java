package com.example.governance.mysql;
import com.example.governance.workflow.DatabaseSyncExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfSystemProperty(named="governance.mysql.test",matches="true")
class MySqlSyncExecutorTest {
    @Test void uniqueKeyReplayAndFailedAppendPreserveTargetData() throws Exception {
        var db = new DriverManagerDataSource(System.getProperty("governance.mysql.url"),System.getProperty("governance.mysql.user"),System.getProperty("governance.mysql.password", ""));
        var jdbc = new JdbcTemplate(db);
        jdbc.execute("CREATE TABLE source_rows (id INT NOT NULL, code VARCHAR(20) NOT NULL, name VARCHAR(40)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE target_rows (id INT PRIMARY KEY, code VARCHAR(20) NOT NULL UNIQUE, name VARCHAR(40)) ENGINE=InnoDB");
        jdbc.update("INSERT INTO source_rows VALUES (1,'A','new'),(2,'B','two')");
        jdbc.update("INSERT INTO target_rows VALUES (1,'A','old')");
        jdbc.execute("CREATE TABLE target_append (id INT PRIMARY KEY, code VARCHAR(20) NOT NULL UNIQUE, name VARCHAR(40)) ENGINE=InnoDB");
        jdbc.update("INSERT INTO target_append VALUES (2,'B','existing')");
        try (var executor = new DatabaseSyncExecutor()) {
            assertEquals(2, executor.upsert(db,db,"SELECT id,code,name FROM source_rows ORDER BY id",List.of(),"target_rows",List.of("id","code","name"),List.of("code"),10));
            assertEquals(2, executor.upsert(db,db,"SELECT id,code,name FROM source_rows ORDER BY id",List.of(),"target_rows",List.of("id","code","name"),List.of("code"),10));
            assertThrows(Exception.class, () -> executor.append(db,db,"SELECT id,code,name FROM source_rows ORDER BY id","target_append",List.of("id","code","name"),1));
        }
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM target_rows",Integer.class));
        assertEquals("new",jdbc.queryForObject("SELECT name FROM target_rows WHERE id=1",String.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM target_append",Integer.class));
        assertEquals("existing",jdbc.queryForObject("SELECT name FROM target_append WHERE id=2",String.class));
    }
}
