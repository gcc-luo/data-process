-- Orchestration rebuild (dev-stage wipe authorized): discard legacy processing task/workflow data,
-- add draft/publish, per-node run state, watermarks and task schedules.
-- FK-safe wipe order.
SET FOREIGN_KEY_CHECKS=0;
DELETE FROM workflow_node_attempts;
DELETE FROM workflow_runs;
DELETE FROM workflow_nodes;
DELETE FROM workflow_schedules;
DELETE FROM workflow_definitions;
DELETE FROM task_run_attempts;
DELETE FROM task_runs;
DELETE FROM execution_logs;
DELETE FROM processing_task_versions;
DELETE FROM processing_tasks;
DELETE FROM task_directories;
SET FOREIGN_KEY_CHECKS=1;

ALTER TABLE task_runs ADD COLUMN parameters LONGTEXT;

ALTER TABLE processing_task_versions ADD COLUMN comment VARCHAR(500);
ALTER TABLE processing_task_versions ADD COLUMN published_by VARCHAR(160);
ALTER TABLE processing_task_versions ADD COLUMN published_at TIMESTAMP;

CREATE TABLE task_drafts (
    task_id BIGINT PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 1,
    definition LONGTEXT NOT NULL,
    updated_by VARCHAR(160) NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (task_id) REFERENCES processing_tasks(id)
);

CREATE TABLE task_node_runs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    attempt_no INTEGER NOT NULL,
    node_key VARCHAR(128) NOT NULL,
    state VARCHAR(20) NOT NULL,
    rows_in BIGINT,
    rows_out BIGINT,
    duration_ms BIGINT,
    error_message VARCHAR(500),
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    UNIQUE KEY uq_task_node_runs (run_id, attempt_no, node_key),
    FOREIGN KEY (run_id) REFERENCES task_runs(id) ON DELETE CASCADE
);

CREATE TABLE task_watermarks (
    task_id BIGINT NOT NULL,
    node_key VARCHAR(128) NOT NULL,
    watermark_value VARCHAR(200),
    tie_breaker_value VARCHAR(200),
    committed_run_id BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (task_id, node_key),
    FOREIGN KEY (task_id) REFERENCES processing_tasks(id)
);

CREATE TABLE task_schedules (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL UNIQUE,
    cron_expression VARCHAR(120) NOT NULL,
    zone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    next_fire_epoch BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (task_id) REFERENCES processing_tasks(id)
);

INSERT INTO permission_catalog (permission_code,module_code,module_name,action_code,action_name,description,sort_order)
SELECT 'workflow.task.publish','workflow.task','任务管理','publish','发布','校验并发布任务版本',COALESCE(MAX(sort_order),0)+1 FROM permission_catalog;
INSERT INTO role_permissions (role_id, permission)
SELECT rp.role_id, 'workflow.task.publish' FROM role_permissions rp
WHERE rp.permission='workflow.task.update'
  AND NOT EXISTS (SELECT 1 FROM role_permissions x WHERE x.role_id=rp.role_id AND x.permission='workflow.task.publish');

UPDATE menus SET name='任务编排' WHERE id=31;