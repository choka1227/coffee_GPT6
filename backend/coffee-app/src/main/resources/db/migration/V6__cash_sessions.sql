CREATE TABLE cash_sessions(
  id VARCHAR(36) PRIMARY KEY,
  branch_id VARCHAR(36) NOT NULL REFERENCES branches(id),
  status VARCHAR(8) NOT NULL CHECK(status IN ('OPEN','CLOSED')),
  opening_float INTEGER NOT NULL CHECK(opening_float>=0),
  opened_by VARCHAR(36) NOT NULL,
  opened_at BIGINT NOT NULL,
  closed_by VARCHAR(36),
  closed_at BIGINT,
  counted_amount INTEGER CHECK(counted_amount>=0),
  expected_amount INTEGER,
  variance INTEGER,
  note VARCHAR(200) NOT NULL DEFAULT ''
);

-- H2 2.x rejects PostgreSQL partial-index syntax. Correctness is enforced by the
-- branch row lock plus the service-level OPEN check; this index supports the lookup.
CREATE INDEX idx_cash_sessions_open ON cash_sessions(branch_id,status);
CREATE INDEX idx_cash_sessions_branch_opened ON cash_sessions(branch_id,opened_at);

ALTER TABLE orders ADD COLUMN cash_session_id VARCHAR(36) REFERENCES cash_sessions(id);
CREATE INDEX idx_orders_cash_session ON orders(cash_session_id);

INSERT INTO role_permissions(role_code,permission)
  SELECT r.code,'CASH_SESSION'
  FROM roles r
  WHERE r.code IN ('HQ','MANAGER','CASHIER')
    AND NOT EXISTS(
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_code=r.code AND rp.permission='CASH_SESSION'
    );
