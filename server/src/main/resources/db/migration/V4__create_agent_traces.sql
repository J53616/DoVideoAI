CREATE TABLE IF NOT EXISTS agent_traces (
    trace_id VARCHAR(36) NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    media_id BIGINT NULL,
    user_id BIGINT NULL,
    task_type VARCHAR(64) NOT NULL,
    goal_digest VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    submitted_at_epoch_ms BIGINT NOT NULL,
    snapshot JSON NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (trace_id),
    UNIQUE KEY uk_agent_trace_task (task_id),
    KEY idx_agent_trace_media_time (media_id, updated_at),
    KEY idx_agent_trace_media_goal_time (media_id, goal_digest, updated_at),
    KEY idx_agent_trace_user_time (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
