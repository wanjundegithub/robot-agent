INSERT INTO `role` (`code`, `name`, `description`)
SELECT 'workflow_admin', 'Workflow Admin', 'Can manage workflow definitions and versions.'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `role` WHERE `code` = 'workflow_admin');

INSERT INTO `role` (`code`, `name`, `description`)
SELECT 'knowledge_admin', 'Knowledge Admin', 'Can manage knowledge bases and versions.'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `role` WHERE `code` = 'knowledge_admin');

INSERT INTO `role` (`code`, `name`, `description`)
SELECT 'viewer', 'Viewer', 'Read-only access to workflow resources.'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `role` WHERE `code` = 'viewer');

INSERT INTO `user_role` (`user_id`, `role_id`, `workspace_id`)
SELECT 'demo-admin', `id`, 1
FROM `role`
WHERE `code` = 'workflow_admin'
  AND NOT EXISTS (
      SELECT 1 FROM `user_role`
      WHERE `user_id` = 'demo-admin' AND `role_id` = `role`.`id` AND `workspace_id` = 1
  );

INSERT INTO `user_role` (`user_id`, `role_id`, `workspace_id`)
SELECT 'demo-admin', `id`, 1
FROM `role`
WHERE `code` = 'knowledge_admin'
  AND NOT EXISTS (
      SELECT 1 FROM `user_role`
      WHERE `user_id` = 'demo-admin' AND `role_id` = `role`.`id` AND `workspace_id` = 1
  );

INSERT INTO `user_role` (`user_id`, `role_id`, `workspace_id`)
SELECT 'demo-user', `id`, 1
FROM `role`
WHERE `code` = 'viewer'
  AND NOT EXISTS (
      SELECT 1 FROM `user_role`
      WHERE `user_id` = 'demo-user' AND `role_id` = `role`.`id` AND `workspace_id` = 1
  );

INSERT INTO `knowledge_base` (
    `workspace_id`,
    `kb_code`,
    `name`,
    `description`,
    `embedding_model`,
    `current_version`,
    `status`,
    `created_by`
)
SELECT
    1,
    'flight_policy_kb',
    'Flight Policy KB',
    'Flight booking and change policy knowledge base.',
    'demo-embedding-model',
    '1.0.0',
    'ACTIVE',
    'system'
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM `knowledge_base` WHERE `workspace_id` = 1 AND `kb_code` = 'flight_policy_kb'
);

INSERT INTO `knowledge_version` (
    `kb_code`,
    `version`,
    `status`,
    `chunk_count`,
    `doc_count`,
    `created_by`,
    `published_at`
)
SELECT
    'flight_policy_kb',
    '1.0.0',
    'PUBLISHED',
    4,
    2,
    'system',
    CURRENT_TIMESTAMP
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM `knowledge_version` WHERE `kb_code` = 'flight_policy_kb' AND `version` = '1.0.0'
);
