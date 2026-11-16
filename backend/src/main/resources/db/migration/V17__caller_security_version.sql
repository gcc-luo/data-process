ALTER TABLE service_callers ADD COLUMN user_security_version VARCHAR(36);
UPDATE service_callers SET user_security_version = (SELECT security_version FROM users WHERE users.id = service_callers.user_id);
ALTER TABLE service_callers ALTER COLUMN user_security_version SET NOT NULL;
