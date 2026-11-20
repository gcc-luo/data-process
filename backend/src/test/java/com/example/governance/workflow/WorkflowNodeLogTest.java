package com.example.governance.workflow;

import com.example.governance.GovernanceApplication;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes=GovernanceApplication.class)
@TestPropertySource(properties="spring.datasource.url=jdbc:h2:mem:workflow_node_log;DB_CLOSE_DELAY=-1")
class WorkflowNodeLogTest {
    @Autowired JdbcTemplate jdbc; @Autowired WorkflowRunStore runs; @Autowired WorkflowWorker worker;
    @MockitoBean CamelTaskRunner runner;

    @Test void nodeLogsAndStatsLandOnNodeAttempt() throws Exception {
        jdbc.update("INSERT INTO processing_tasks(id,name,task_type,current_version) VALUES (801,'t','SQL',1)");
        jdbc.update("INSERT INTO processing_task_versions(task_id,version,configuration) VALUES (801,1,'{}')");
        jdbc.update("INSERT INTO workflow_definitions(id,name,current_version) VALUES (801,'wf',1)");
        jdbc.update("INSERT INTO workflow_versions(workflow_id,version) VALUES (801,1)");
        jdbc.update("INSERT INTO workflow_nodes(workflow_id,workflow_version,node_key,ordinal_no,task_id,task_version) VALUES (801,1,'n1',0,801,1)");
        jdbc.update("INSERT INTO workflow_runs(id,workflow_id,workflow_version,business_date,state,created_at) VALUES (801,801,1,DATE '2026-11-20','RUNNING',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO workflow_run_nodes(run_id,node_key,state) VALUES (801,'n1','PENDING')");
        Mockito.doAnswer(inv -> {
            ExecutionSinks sinks = inv.getArgument(3);
            sinks.log().log("INFO", "节点开始");
            sinks.stats().accept("[{\"nodeKey\":\"n1\",\"rowsIn\":3,\"rowsOut\":3,\"durationMs\":5}]");
            return null;
        }).when(runner).execute(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        worker.process(801);
        assertEquals("节点开始", jdbc.queryForObject("SELECT message FROM execution_logs WHERE owner_type='WORKFLOW_NODE' AND owner_id=801 AND node_key='n1'", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT attempt_no FROM execution_logs WHERE owner_id=801", Integer.class));
        assertTrue(jdbc.queryForObject("SELECT stats FROM workflow_node_attempts WHERE run_id=801 AND node_key='n1'", String.class).contains("\"rowsIn\":3"));
    }
}
