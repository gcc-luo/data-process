ALTER TABLE datasets ADD COLUMN department_field VARCHAR(255);
CREATE TABLE dataset_role_rules (
dataset_id BIGINT NOT NULL,
role_id BIGINT NOT NULL,
    scope VARCHAR(32) NOT NULL,
    PRIMARY KEY(dataset_id, role_id),
    FOREIGN KEY (dataset_id) REFERENCES datasets(id),
    FOREIGN KEY (role_id) REFERENCES roles(id)
);
CREATE TABLE dataset_role_fields (
    dataset_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    field_name VARCHAR(255) NOT NULL,
    access_mode VARCHAR(16) NOT NULL,
    PRIMARY KEY(dataset_id, role_id, field_name),
    FOREIGN KEY(dataset_id, role_id) REFERENCES dataset_role_rules(dataset_id, role_id) ON DELETE CASCADE,
    FOREIGN KEY(dataset_id, field_name) REFERENCES dataset_fields(dataset_id, field_name)
);
