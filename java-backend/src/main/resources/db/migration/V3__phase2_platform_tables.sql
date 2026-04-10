CREATE TABLE IF NOT EXISTS `knowledge_base` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `workspace_id` BIGINT NOT NULL,
    `kb_code` VARCHAR(64) NOT NULL,
    `name` VARCHAR(128) NOT NULL,
    `description` TEXT,
    `embedding_model` VARCHAR(64),
    `current_version` VARCHAR(32),
    `status` ENUM('ACTIVE', 'INACTIVE') DEFAULT 'ACTIVE',
    `created_by` VARCHAR(64),
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_workspace_kb` (`workspace_id`, `kb_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `knowledge_version` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `kb_code` VARCHAR(64) NOT NULL,
    `version` VARCHAR(32) NOT NULL,
    `status` ENUM('DRAFT', 'PUBLISHED', 'ARCHIVED') DEFAULT 'DRAFT',
    `chunk_count` INT DEFAULT 0,
    `doc_count` INT DEFAULT 0,
    `created_by` VARCHAR(64),
    `published_at` TIMESTAMP,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_kb_version` (`kb_code`, `version`),
    KEY `idx_kv_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `knowledge_document` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `kb_code` VARCHAR(64) NOT NULL,
    `version` VARCHAR(32) NOT NULL,
    `doc_id` VARCHAR(64) NOT NULL,
    `filename` VARCHAR(256),
    `file_size` BIGINT,
    `file_url` VARCHAR(512),
    `status` ENUM('PENDING', 'PROCESSING', 'PROCESSED', 'FAILED') DEFAULT 'PENDING',
    `chunk_count` INT,
    `error_message` TEXT,
    `uploaded_at` TIMESTAMP,
    `processed_at` TIMESTAMP,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    KEY `idx_kd_kb_version` (`kb_code`, `version`),
    KEY `idx_kd_doc_id` (`doc_id`),
    KEY `idx_kd_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `audit_log` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `workspace_id` BIGINT NOT NULL,
    `user_id` VARCHAR(64),
    `action` VARCHAR(64) NOT NULL,
    `resource_type` VARCHAR(64),
    `resource_id` VARCHAR(128),
    `ip_address` VARCHAR(64),
    `user_agent` TEXT,
    `request_data` TEXT,
    `response_status` INT,
    `execution_time_ms` INT,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    KEY `idx_audit_workspace` (`workspace_id`),
    KEY `idx_audit_user` (`user_id`),
    KEY `idx_audit_resource` (`resource_type`, `resource_id`),
    KEY `idx_audit_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `role` (
    `id` INT PRIMARY KEY AUTO_INCREMENT,
    `code` VARCHAR(32) UNIQUE,
    `name` VARCHAR(64),
    `description` TEXT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `tool_permission` (
    `id` INT PRIMARY KEY AUTO_INCREMENT,
    `tool_code` VARCHAR(64),
    `action` VARCHAR(16),
    `resource` VARCHAR(64),
    UNIQUE KEY `uk_tool_action` (`tool_code`, `action`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `role_permission` (
    `role_id` INT,
    `permission_id` INT,
    PRIMARY KEY (`role_id`, `permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `user_role` (
    `user_id` VARCHAR(64),
    `role_id` INT,
    `workspace_id` BIGINT,
    PRIMARY KEY (`user_id`, `role_id`, `workspace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
