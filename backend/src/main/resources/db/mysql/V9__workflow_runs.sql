CREATE TABLE workflow_runs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    workflow_id BIGINT NOT NULL,
    workflow_version INTEGER NOT NULL,
    business_date DATE NOT NULL,
    state VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    FOREIGN KEY(workflow_id, workflow_version) REFERENCES workflow_versions(workflow_id, version)
);
CREATE TABLE workflow_run_nodes (
run_id BIGINT NOT NULL,
    node_key VARCHAR(128) NOT NULL,
    state VARCHAR(20) NOT NULL,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    PRIMARY KEY(run_id, node_key),
    FOREIGN KEY (run_id) REFERENCES workflow_runs(id)
);
