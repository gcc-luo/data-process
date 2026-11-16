CREATE TABLE departments (
    id BIGINT PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
parent_id BIGINT,
    CONSTRAINT department_not_own_parent CHECK (parent_id IS NULL OR parent_id <> id),
    FOREIGN KEY (parent_id) REFERENCES departments(id)
);
CREATE TABLE users (
    id BIGINT PRIMARY KEY,
    username VARCHAR(160) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
department_id BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL,
    FOREIGN KEY (department_id) REFERENCES departments(id)
);
CREATE TABLE roles (
    id BIGINT PRIMARY KEY,
    name VARCHAR(160) NOT NULL UNIQUE
);
CREATE TABLE user_roles (
user_id BIGINT NOT NULL,
role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    FOREIGN KEY (user_id) REFERENCES users(id),
    FOREIGN KEY (role_id) REFERENCES roles(id)
);
CREATE TABLE role_permissions (
role_id BIGINT NOT NULL,
    permission VARCHAR(160) NOT NULL,
    PRIMARY KEY (role_id, permission),
    FOREIGN KEY (role_id) REFERENCES roles(id)
);
CREATE TABLE audit_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
actor_id BIGINT,
    occurred_at TIMESTAMP NOT NULL,
    action VARCHAR(160) NOT NULL,
    object_type VARCHAR(160) NOT NULL,
    object_id VARCHAR(160) NOT NULL,
    result VARCHAR(32) NOT NULL,
    FOREIGN KEY (actor_id) REFERENCES users(id)
);
