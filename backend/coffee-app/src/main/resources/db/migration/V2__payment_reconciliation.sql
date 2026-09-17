CREATE TABLE payment_reconciliations(
  id VARCHAR(36) PRIMARY KEY,
  order_id VARCHAR(20) NOT NULL REFERENCES orders(id),
  trigger_source VARCHAR(12) NOT NULL CHECK(trigger_source IN ('SCHEDULED','MANUAL')),
  actor_id VARCHAR(36),
  outcome VARCHAR(16) NOT NULL CHECK(outcome IN ('CONFIRMED','STILL_UNPAID','AMOUNT_MISMATCH','SIMULATED','QUERY_FAILED')),
  trade_status VARCHAR(20) NOT NULL,
  trade_amount INTEGER,
  provider_trade_no VARCHAR(64) NOT NULL,
  detail VARCHAR(200) NOT NULL,
  queried_at BIGINT NOT NULL
);
CREATE INDEX idx_reconciliations_order ON payment_reconciliations(order_id,queried_at);
CREATE INDEX idx_reconciliations_queried ON payment_reconciliations(queried_at);
CREATE INDEX idx_orders_pending_online ON orders(payment_method,status,created_at);
INSERT INTO role_permissions(role_code,permission)
  SELECT code,'PAYMENT_RECONCILE' FROM roles WHERE code IN ('HQ','MANAGER');
