CREATE TABLE reports (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
owner_id BIGINT NOT NULL,
dataset_id BIGINT NOT NULL,
    name VARCHAR(160) NOT NULL,
    chart_type VARCHAR(16) NOT NULL,
    query_kind VARCHAR(16) NOT NULL,
    query_config LONGTEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    FOREIGN KEY (owner_id) REFERENCES users(id),
    FOREIGN KEY (dataset_id) REFERENCES datasets(id)
);
CREATE TABLE dashboards (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
owner_id BIGINT NOT NULL,
    name VARCHAR(160) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    FOREIGN KEY (owner_id) REFERENCES users(id)
);
CREATE TABLE dashboard_reports (
dashboard_id BIGINT NOT NULL,
report_id BIGINT NOT NULL,
    ordinal_no INTEGER NOT NULL,
    PRIMARY KEY(dashboard_id, report_id),
    UNIQUE(dashboard_id, ordinal_no),
    FOREIGN KEY (dashboard_id) REFERENCES dashboards(id),
    FOREIGN KEY (report_id) REFERENCES reports(id)
);
