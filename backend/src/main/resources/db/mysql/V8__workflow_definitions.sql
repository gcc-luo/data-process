CREATE TABLE workflow_definitions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    current_version INTEGER NOT NULL
);
CREATE TABLE workflow_versions (
workflow_id BIGINT NOT NULL,
    version INTEGER NOT NULL,
    PRIMARY KEY(workflow_id, version),
    FOREIGN KEY (workflow_id) REFERENCES workflow_definitions(id)
);
CREATE TABLE workflow_nodes (
    workflow_id BIGINT NOT NULL,
    workflow_version INTEGER NOT NULL,
    node_key VARCHAR(128) NOT NULL,
    ordinal_no INTEGER NOT NULL,
    task_id BIGINT NOT NULL,
    task_version INTEGER NOT NULL,
    PRIMARY KEY(workflow_id, workflow_version, node_key),
    UNIQUE(workflow_id, workflow_version, ordinal_no),
    FOREIGN KEY(workflow_id, workflow_version) REFERENCES workflow_versions(workflow_id, version),
    FOREIGN KEY(task_id, task_version) REFERENCES processing_task_versions(task_id, version)
);
CREATE TABLE workflow_edges (
    workflow_id BIGINT NOT NULL,
    workflow_version INTEGER NOT NULL,
    ordinal_no INTEGER NOT NULL,
    from_key VARCHAR(128) NOT NULL,
    to_key VARCHAR(128) NOT NULL,
    PRIMARY KEY(workflow_id, workflow_version, from_key, to_key),
    UNIQUE(workflow_id, workflow_version, ordinal_no),
    FOREIGN KEY(workflow_id, workflow_version, from_key) REFERENCES workflow_nodes(workflow_id, workflow_version, node_key),
    FOREIGN KEY(workflow_id, workflow_version, to_key) REFERENCES workflow_nodes(workflow_id, workflow_version, node_key)
);
