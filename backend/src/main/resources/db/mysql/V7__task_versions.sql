CREATE TABLE processing_tasks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    current_version INTEGER NOT NULL
);
CREATE TABLE processing_task_versions (
task_id BIGINT NOT NULL,
    version INTEGER NOT NULL,
    configuration LONGTEXT NOT NULL,
    PRIMARY KEY(task_id, version),
    FOREIGN KEY (task_id) REFERENCES processing_tasks(id)
);
