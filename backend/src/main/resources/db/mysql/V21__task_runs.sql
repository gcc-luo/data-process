CREATE TABLE task_runs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    task_version INTEGER NOT NULL,
    business_date DATE NOT NULL,
    state VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    error_message VARCHAR(255),
    FOREIGN KEY(task_id, task_version) REFERENCES processing_task_versions(task_id, version)
);
