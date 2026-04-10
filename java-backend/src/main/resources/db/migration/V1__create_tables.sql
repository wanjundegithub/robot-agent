CREATE TABLE IF NOT EXISTS `session` (
    `id` VARCHAR(64) PRIMARY KEY,
    `workspace_id` BIGINT NOT NULL,
    `user_id` VARCHAR(64) NOT NULL,
    `status` ENUM('ACTIVE', 'CLOSED', 'EXPIRED') DEFAULT 'ACTIVE',
    `current_execution_id` VARCHAR(64),
    `suspended_stack` JSON,
    `variables` JSON,
    `expires_at` TIMESTAMP,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `last_activity_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY `idx_user` (`user_id`),
    KEY `idx_status` (`status`),
    KEY `idx_expires` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `workflow_definition` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `workspace_id` BIGINT NOT NULL,
    `workflow_code` VARCHAR(64) NOT NULL,
    `name` VARCHAR(128) NOT NULL,
    `description` TEXT,
    `status` ENUM('DRAFT', 'PUBLISHED', 'ARCHIVED') DEFAULT 'DRAFT',
    `current_version` VARCHAR(32),
    `created_by` VARCHAR(64),
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_workspace_code` (`workspace_id`, `workflow_code`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `workflow_version` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `workflow_code` VARCHAR(64) NOT NULL,
    `version` VARCHAR(32) NOT NULL,
    `status` ENUM('DRAFT', 'PUBLISHED', 'ARCHIVED') DEFAULT 'DRAFT',
    `definition` JSON NOT NULL,
    `entry_rule` JSON,
    `config` JSON,
    `created_by` VARCHAR(64),
    `published_at` TIMESTAMP,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_workflow_version` (`workflow_code`, `version`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `execution` (
    `id` VARCHAR(64) PRIMARY KEY,
    `session_id` VARCHAR(64) NOT NULL,
    `workflow_code` VARCHAR(64) NOT NULL,
    `workflow_version` VARCHAR(32) NOT NULL,
    `status` ENUM('pending', 'running', 'suspended', 'completed', 'failed', 'cancelled') DEFAULT 'pending',
    `current_node_id` VARCHAR(64),
    `input_variables` JSON,
    `output_variables` JSON,
    `variables` JSON,
    `started_at` TIMESTAMP,
    `completed_at` TIMESTAMP,
    `error` TEXT,
    `metrics` JSON,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    KEY `idx_session` (`session_id`),
    KEY `idx_workflow` (`workflow_code`),
    KEY `idx_status` (`status`),
    KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `execution_node_log` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `execution_id` VARCHAR(64) NOT NULL,
    `node_id` VARCHAR(64) NOT NULL,
    `node_type` VARCHAR(32) NOT NULL,
    `status` ENUM('pending', 'running', 'completed', 'failed', 'skipped') DEFAULT 'pending',
    `input` JSON,
    `output` JSON,
    `error` TEXT,
    `started_at` TIMESTAMP,
    `completed_at` TIMESTAMP,
    `metrics` JSON,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    KEY `idx_execution` (`execution_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
