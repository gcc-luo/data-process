CREATE TABLE workflow_node_attempts (
    run_id BIGINT NOT NULL,
    node_key VARCHAR(128) NOT NULL,
    attempt_no INTEGER NOT NULL,
    state VARCHAR(20) NOT NULL,
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP,
    PRIMARY KEY(run_id, node_key, attempt_no),
    FOREIGN KEY(run_id, node_key) REFERENCES workflow_run_nodes(run_id, node_key)
);
