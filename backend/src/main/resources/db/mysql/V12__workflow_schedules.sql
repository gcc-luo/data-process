CREATE TABLE workflow_schedules (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    workflow_id BIGINT NOT NULL,
    workflow_version INTEGER NOT NULL,
    cron_expression VARCHAR(128) NOT NULL,
    zone_id VARCHAR(128) NOT NULL,
    next_fire_epoch BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL,
    FOREIGN KEY(workflow_id, workflow_version) REFERENCES workflow_versions(workflow_id, version)
);
