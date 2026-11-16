CREATE TABLE data_services (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
creator_id BIGINT NOT NULL,
dataset_id BIGINT NOT NULL,
    name VARCHAR(160) NOT NULL,
    fields_json LONGTEXT NOT NULL,
    row_limit INTEGER NOT NULL,
    state VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    FOREIGN KEY (creator_id) REFERENCES users(id),
    FOREIGN KEY (dataset_id) REFERENCES datasets(id)
);
CREATE TABLE service_callers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(160) NOT NULL UNIQUE,
user_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    enabled BOOLEAN NOT NULL,
    limit_per_minute INTEGER NOT NULL,
    window_epoch BIGINT NOT NULL,
    window_count INTEGER NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id)
);
CREATE TABLE service_grants (
service_id BIGINT NOT NULL,
caller_id BIGINT NOT NULL,
    PRIMARY KEY(service_id, caller_id),
    FOREIGN KEY (service_id) REFERENCES data_services(id),
    FOREIGN KEY (caller_id) REFERENCES service_callers(id)
);
CREATE TABLE service_invocations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
service_id BIGINT NOT NULL,
caller_id BIGINT NOT NULL,
    started_at TIMESTAMP NOT NULL,
    duration_ms BIGINT,
    outcome VARCHAR(20) NOT NULL,
    FOREIGN KEY (service_id) REFERENCES data_services(id),
    FOREIGN KEY (caller_id) REFERENCES service_callers(id)
);
