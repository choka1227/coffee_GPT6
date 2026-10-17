-- 1) 訂單上的買受人發票資訊（coffee-orders 擁有）。舊訂單全為 NULL。
ALTER TABLE orders ADD COLUMN invoice_type VARCHAR(8);
ALTER TABLE orders ADD COLUMN invoice_email VARCHAR(100);
ALTER TABLE orders ADD COLUMN invoice_carrier VARCHAR(8);
ALTER TABLE orders ADD COLUMN invoice_love_code VARCHAR(7);
ALTER TABLE orders ADD CONSTRAINT ck_orders_invoice_type
  CHECK(invoice_type IS NULL OR invoice_type IN ('CLOUD','MOBILE','DONATE'));

-- 2) 發票（coffee-payments 擁有）。一張訂單最多一張發票。
CREATE TABLE invoices(
  order_id VARCHAR(20) PRIMARY KEY REFERENCES orders(id),
  branch_id VARCHAR(36) NOT NULL REFERENCES branches(id),
  amount INTEGER NOT NULL,
  status VARCHAR(12) NOT NULL,
  invoice_no VARCHAR(10),
  invoice_date VARCHAR(19),
  random_number VARCHAR(4),
  attempts INTEGER NOT NULL DEFAULT 0,
  uncertain BOOLEAN NOT NULL DEFAULT FALSE,
  claim_token VARCHAR(36),
  claimed_until BIGINT,
  inflight_until BIGINT,
  last_error VARCHAR(200),
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  issued_at BIGINT,
  CONSTRAINT uq_invoices_invoice_no UNIQUE(invoice_no),
  CONSTRAINT ck_invoices_amount CHECK(amount>0),
  CONSTRAINT ck_invoices_attempts CHECK(attempts>=0),
  CONSTRAINT ck_invoices_status CHECK(status IN ('PENDING','ISSUED','FAILED','SKIPPED')),
  CONSTRAINT ck_invoices_issued CHECK(status<>'ISSUED' OR (invoice_no IS NOT NULL AND invoice_date IS NOT NULL)),
  CONSTRAINT ck_invoices_claim CHECK((claim_token IS NULL AND claimed_until IS NULL) OR (claim_token IS NOT NULL AND claimed_until IS NOT NULL AND status='PENDING')),
  CONSTRAINT ck_invoices_inflight CHECK(inflight_until IS NULL OR status IN ('PENDING','FAILED'))
);
CREATE INDEX idx_invoices_status ON invoices(status,updated_at);
CREATE INDEX idx_invoices_branch_status ON invoices(branch_id,status,updated_at);

-- 3) 掃描游標。G02 只用 name='PAID'；G03 會新增 name='REFUNDED' 一列（insert，不改表）。
CREATE TABLE invoice_cursors(
  name VARCHAR(16) PRIMARY KEY,
  at_ms BIGINT NOT NULL,
  order_id VARCHAR(20) NOT NULL
);
