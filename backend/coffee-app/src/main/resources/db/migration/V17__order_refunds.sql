ALTER TABLE orders ADD COLUMN refunded_at BIGINT;
ALTER TABLE orders ADD COLUMN refunded_by VARCHAR(36) REFERENCES accounts(id);
ALTER TABLE orders ADD COLUMN refund_reason VARCHAR(200);
ALTER TABLE orders ADD COLUMN refund_provider_note VARCHAR(200);
ALTER TABLE orders ADD COLUMN refund_cash_session_id VARCHAR(36) REFERENCES cash_sessions(id);
ALTER TABLE orders ADD CONSTRAINT ck_orders_refund
  CHECK(refunded_at IS NULL OR (refunded_by IS NOT NULL AND refund_reason IS NOT NULL AND paid_at IS NOT NULL));
CREATE INDEX idx_orders_refunded ON orders(refunded_at);
CREATE INDEX idx_orders_branch_refunded ON orders(branch_id,refunded_at);
CREATE INDEX idx_orders_refund_session ON orders(refund_cash_session_id);

CREATE TABLE invoice_reversals(
  order_id VARCHAR(20) PRIMARY KEY REFERENCES invoices(order_id),
  branch_id VARCHAR(36) NOT NULL REFERENCES branches(id),
  kind VARCHAR(10),
  status VARCHAR(10) NOT NULL,
  allowance_no VARCHAR(20),
  attempts INTEGER NOT NULL DEFAULT 0,
  uncertain BOOLEAN NOT NULL DEFAULT FALSE,
  claim_token VARCHAR(36),
  claimed_until BIGINT,
  inflight_until BIGINT,
  last_error VARCHAR(200),
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  done_at BIGINT,
  CONSTRAINT ck_invoice_reversals_attempts CHECK(attempts>=0),
  CONSTRAINT ck_invoice_reversals_claim CHECK((claim_token IS NULL AND claimed_until IS NULL) OR (claim_token IS NOT NULL AND claimed_until IS NOT NULL AND status='PENDING')),
  CONSTRAINT ck_invoice_reversals_kind CHECK(kind IS NULL OR kind IN ('VOID','ALLOWANCE','NONE')),
  CONSTRAINT ck_invoice_reversals_status CHECK(status IN ('PENDING','DONE','FAILED')),
  CONSTRAINT ck_invoice_reversals_done CHECK(status<>'DONE' OR kind IS NOT NULL),
  CONSTRAINT ck_invoice_reversals_inflight CHECK(inflight_until IS NULL OR status IN ('PENDING','FAILED'))
);
CREATE INDEX idx_invoice_reversals_status ON invoice_reversals(status,updated_at);
CREATE INDEX idx_invoice_reversals_branch ON invoice_reversals(branch_id,status,updated_at);

INSERT INTO role_permissions(role_code,permission)
  SELECT code,'ORDER_REFUND' FROM roles WHERE code IN ('HQ','MANAGER')
    AND NOT EXISTS(SELECT 1 FROM role_permissions rp
      WHERE rp.role_code=roles.code AND rp.permission='ORDER_REFUND');
