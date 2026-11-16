CREATE TABLE data_sources (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(120) NOT NULL UNIQUE,
    source_type VARCHAR(20) NOT NULL,
    host VARCHAR(253) NOT NULL,
    port INTEGER NOT NULL,
    database_name VARCHAR(128) NOT NULL,
    username VARCHAR(128) NOT NULL,
    password_cipher VARCHAR(4096) NOT NULL
);
