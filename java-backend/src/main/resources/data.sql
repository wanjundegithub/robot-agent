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
