package com.example.governance.mysql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfSystemProperty(named="governance.mysql.test",matches="true")
class MySqlTaskUpgradeTest {
    @Test void upgradesExistingFailedTaskAndBackfillsItsAttempt() throws Exception {
        String url=System.getProperty("governance.mysql.url"), user=System.getProperty("governance.mysql.user"), password=System.getProperty("governance.mysql.password","");
        Flyway.configure().dataSource(url,user,password).locations("classpath:db/mysql").target("21").load().migrate();
        try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement()) {
            s.execute("INSERT INTO processing_tasks(id,name,task_type,current_version) VALUES (1,'legacy','ORCH',1)");
            s.execute("INSERT INTO processing_task_versions(task_id,version,configuration) VALUES (1,1,'{}')");
            s.execute("INSERT INTO task_runs(id,task_id,task_version,business_date,state,created_at,error_message) VALUES (1,1,1,'2026-11-16','FAILED',CURRENT_TIMESTAMP,'previous failure')");
        }
        // V35 编排重建会清空历史任务/运行数据，回填断言只验证到 V34
        Flyway.configure().dataSource(url,user,password).locations("classpath:db/mysql").target("34").load().migrate();
        try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement();var rs=s.executeQuery("SELECT attempt_no,state,error_message FROM task_run_attempts WHERE run_id=1")) {
            assertTrue(rs.next()); assertEquals(1,rs.getInt(1)); assertEquals("FAILED",rs.getString(2)); assertEquals("previous failure",rs.getString(3)); assertFalse(rs.next());
        }
    }
}
