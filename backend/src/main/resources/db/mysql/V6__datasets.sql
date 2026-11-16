CREATE TABLE datasets (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
source_id BIGINT NOT NULL,
    name VARCHAR(160) NOT NULL UNIQUE,
    catalog_name VARCHAR(255),
    schema_name VARCHAR(255),
    table_name VARCHAR(255) NOT NULL,
    FOREIGN KEY (source_id) REFERENCES data_sources(id)
);
CREATE TABLE dataset_fields (
dataset_id BIGINT NOT NULL,
    ordinal_no INTEGER NOT NULL,
    field_name VARCHAR(255) NOT NULL,
    data_type VARCHAR(128) NOT NULL,
    nullable BOOLEAN NOT NULL,
    PRIMARY KEY (dataset_id, field_name),
    UNIQUE (dataset_id, ordinal_no),
    FOREIGN KEY (dataset_id) REFERENCES datasets(id) ON DELETE CASCADE
);
