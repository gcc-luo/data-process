ALTER TABLE task_runs ADD COLUMN attempt_no INTEGER NOT NULL DEFAULT 1;
CREATE TABLE task_run_attempts (
    run_id BIGINT NOT NULL REFERENCES task_runs(id) ON DELETE CASCADE,
    attempt_no INTEGER NOT NULL,
    state VARCHAR(20) NOT NULL,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    error_message VARCHAR(255),
    PRIMARY KEY (run_id, attempt_no)
);
INSERT INTO task_run_attempts(run_id,attempt_no,state,started_at,finished_at,error_message)
SELECT id,1,state,started_at,finished_at,error_message FROM task_runs;
