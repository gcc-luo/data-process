CREATE TABLE realtime_jobs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
source_id BIGINT NOT NULL,
target_id BIGINT NOT NULL,
    config LONGTEXT NOT NULL,
    interval_seconds INTEGER NOT NULL,
    state VARCHAR(16) NOT NULL,
    next_poll_epoch BIGINT NOT NULL,
    cursor_watermark VARCHAR(128),
    cursor_tie BIGINT,
    processed BIGINT NOT NULL,
    last_success TIMESTAMP,
    last_error VARCHAR(255),
    FOREIGN KEY (source_id) REFERENCES data_sources(id),
    FOREIGN KEY (target_id) REFERENCES data_sources(id)
);
