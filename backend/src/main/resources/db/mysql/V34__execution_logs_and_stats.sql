-- Execution logs and per-attempt node stats for task and workflow runs.
CREATE TABLE execution_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_type VARCHAR(20) NOT NULL,
    owner_id BIGINT NOT NULL,
    node_key VARCHAR(128),
    attempt_no INTEGER NOT NULL,
    line_no INTEGER NOT NULL,
    level VARCHAR(10) NOT NULL,
    message VARCHAR(2000) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_execution_logs_owner ON execution_logs(owner_type, owner_id, node_key, attempt_no, line_no);
ALTER TABLE task_run_attempts ADD COLUMN stats TEXT;
ALTER TABLE workflow_node_attempts ADD COLUMN stats TEXT;
