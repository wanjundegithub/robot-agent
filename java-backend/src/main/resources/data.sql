INSERT INTO role (code, name, description)
SELECT 'workflow_admin', 'Workflow Admin', 'Can manage workflow definitions and versions.'
WHERE NOT EXISTS (SELECT 1 FROM role WHERE code = 'workflow_admin');

INSERT INTO role (code, name, description)
SELECT 'knowledge_admin', 'Knowledge Admin', 'Can manage knowledge bases and versions.'
WHERE NOT EXISTS (SELECT 1 FROM role WHERE code = 'knowledge_admin');

INSERT INTO role (code, name, description)
SELECT 'viewer', 'Viewer', 'Read-only access to workflow resources.'
WHERE NOT EXISTS (SELECT 1 FROM role WHERE code = 'viewer');

INSERT INTO user_role (user_id, workspace_id, role_id)
SELECT 'demo-admin', 1, r.id
FROM role r
WHERE r.code = 'workflow_admin'
  AND NOT EXISTS (
    SELECT 1 FROM user_role ur
    WHERE ur.user_id = 'demo-admin' AND ur.workspace_id = 1 AND ur.role_id = r.id
  );

INSERT INTO user_role (user_id, workspace_id, role_id)
SELECT 'demo-admin', 1, r.id
FROM role r
WHERE r.code = 'knowledge_admin'
  AND NOT EXISTS (
    SELECT 1 FROM user_role ur
    WHERE ur.user_id = 'demo-admin' AND ur.workspace_id = 1 AND ur.role_id = r.id
  );

INSERT INTO user_role (user_id, workspace_id, role_id)
SELECT 'demo-admin', 1, r.id
FROM role r
WHERE r.code = 'viewer'
  AND NOT EXISTS (
    SELECT 1 FROM user_role ur
    WHERE ur.user_id = 'demo-admin' AND ur.workspace_id = 1 AND ur.role_id = r.id
  );

INSERT INTO user_role (user_id, workspace_id, role_id)
SELECT 'demo-user', 1, r.id
FROM role r
WHERE r.code = 'viewer'
  AND NOT EXISTS (
    SELECT 1 FROM user_role ur
    WHERE ur.user_id = 'demo-user' AND ur.workspace_id = 1 AND ur.role_id = r.id
  );

INSERT INTO user_role (user_id, workspace_id, role_id)
SELECT 'knowledge-admin', 1, r.id
FROM role r
WHERE r.code = 'knowledge_admin'
  AND NOT EXISTS (
    SELECT 1 FROM user_role ur
    WHERE ur.user_id = 'knowledge-admin' AND ur.workspace_id = 1 AND ur.role_id = r.id
  );

INSERT INTO knowledge_base (
  workspace_id,
  kb_code,
  name,
  description,
  embedding_model,
  current_version,
  status,
  created_by,
  created_at
)
SELECT
  1,
  'flight_policy_kb',
  'Flight Policy KB',
  'Flight booking and change policy knowledge base.',
  'embedding-qwen3-8b',
  '1.0.0',
  'ACTIVE',
  'system',
  CURRENT_TIMESTAMP
WHERE NOT EXISTS (
  SELECT 1 FROM knowledge_base WHERE workspace_id = 1 AND kb_code = 'flight_policy_kb'
);

INSERT INTO knowledge_version (
  kb_code,
  version,
  status,
  chunk_count,
  doc_count,
  created_by,
  published_at,
  created_at
)
SELECT
  'flight_policy_kb',
  '1.0.0',
  'PUBLISHED',
  4,
  2,
  'system',
  CURRENT_TIMESTAMP,
  CURRENT_TIMESTAMP
WHERE NOT EXISTS (
  SELECT 1 FROM knowledge_version WHERE kb_code = 'flight_policy_kb' AND version = '1.0.0'
);

UPDATE knowledge_base
SET current_version = '1.0.0', status = 'ACTIVE'
WHERE workspace_id = 1 AND kb_code = 'flight_policy_kb';
