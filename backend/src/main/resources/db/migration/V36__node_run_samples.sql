-- Persist bounded data-preview samples (<=100 rows/node) for real runs, viewable in console and run records.
ALTER TABLE task_node_runs ADD COLUMN sample_json TEXT;