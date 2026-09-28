CREATE TABLE discounts(
  id VARCHAR(36) PRIMARY KEY,
  code VARCHAR(20) NOT NULL UNIQUE,
  name VARCHAR(40) NOT NULL,
  kind VARCHAR(8) NOT NULL CHECK(kind IN ('PERCENT','AMOUNT')),
  percent INTEGER NOT NULL DEFAULT 0 CHECK(percent>=0 AND percent<=90),
  amount INTEGER NOT NULL DEFAULT 0 CHECK(amount>=0),
  min_subtotal INTEGER NOT NULL DEFAULT 0 CHECK(min_subtotal>=0),
  branch_id VARCHAR(36) REFERENCES branches(id),
  starts_at BIGINT,
  ends_at BIGINT,
  max_redemptions INTEGER,
  redeemed_count INTEGER NOT NULL DEFAULT 0 CHECK(redeemed_count>=0),
  active BOOLEAN NOT NULL,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  CHECK(max_redemptions IS NULL OR max_redemptions>0)
);

CREATE INDEX idx_discounts_code_active ON discounts(code, active);

CREATE TABLE order_discounts(
  order_id VARCHAR(20) PRIMARY KEY REFERENCES orders(id),
  discount_id VARCHAR(36) NOT NULL,
  code VARCHAR(20) NOT NULL,
  name VARCHAR(40) NOT NULL,
  kind VARCHAR(8) NOT NULL,
  percent INTEGER NOT NULL,
  amount INTEGER NOT NULL,
  subtotal INTEGER NOT NULL,
  discount_amount INTEGER NOT NULL CHECK(discount_amount>=0),
  created_at BIGINT NOT NULL
);

ALTER TABLE orders ADD COLUMN discount_amount INTEGER NOT NULL DEFAULT 0;
