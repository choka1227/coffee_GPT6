ALTER TABLE audit_log ADD COLUMN branch_id VARCHAR(36);
ALTER TABLE audit_log ADD COLUMN actor_name VARCHAR(80) NOT NULL DEFAULT '';
ALTER TABLE audit_log ADD COLUMN summary VARCHAR(200) NOT NULL DEFAULT '';

CREATE INDEX idx_audit_created ON audit_log(created_at);
CREATE INDEX idx_audit_branch_created ON audit_log(branch_id,created_at);
CREATE INDEX idx_audit_action_created ON audit_log(action,created_at);

INSERT INTO role_permissions(role_code,permission)
  SELECT code,'AUDIT_VIEW' FROM roles WHERE code IN ('HQ','MANAGER');
