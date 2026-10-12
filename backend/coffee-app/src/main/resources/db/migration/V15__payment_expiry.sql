CREATE TABLE payment_expiries(
  order_id VARCHAR(20) PRIMARY KEY REFERENCES orders(id),
  expired_at BIGINT NOT NULL,
  recheck_attempts INTEGER NOT NULL DEFAULT 0 CHECK(recheck_attempts>=0),
  rechecked_at BIGINT,
  recheck_outcome VARCHAR(12) CHECK(recheck_outcome IN ('UNPAID','PAID','SIMULATED','GAVE_UP'))
);
CREATE INDEX idx_payment_expiries_recheck ON payment_expiries(rechecked_at,expired_at);

CREATE TABLE payment_anomalies(
  id VARCHAR(36) PRIMARY KEY,
  order_id VARCHAR(20) NOT NULL REFERENCES orders(id),
  branch_id VARCHAR(36) NOT NULL REFERENCES branches(id),
  order_total INTEGER NOT NULL,
  kind VARCHAR(24) NOT NULL CHECK(kind IN ('PAID_AFTER_EXPIRY')),
  source VARCHAR(12) NOT NULL CHECK(source IN ('CALLBACK','RECHECK')),
  provider_trade_no VARCHAR(64) NOT NULL,
  trade_amount INTEGER,
  detected_at BIGINT NOT NULL,
  resolved_at BIGINT,
  resolved_by VARCHAR(36),
  resolution_note VARCHAR(200),
  UNIQUE(order_id,kind)
);
CREATE INDEX idx_payment_anomalies_open ON payment_anomalies(resolved_at,detected_at);
CREATE INDEX idx_payment_anomalies_branch ON payment_anomalies(branch_id,resolved_at,detected_at);
