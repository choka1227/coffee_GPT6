CREATE TABLE item_promotions(
  id VARCHAR(36) PRIMARY KEY,
  name VARCHAR(40) NOT NULL,
  kind VARCHAR(12) NOT NULL CHECK(kind IN ('ITEM_PERCENT','NTH_PERCENT')),
  percent INTEGER NOT NULL CHECK(percent>=1 AND percent<=100),
  nth INTEGER NOT NULL DEFAULT 0 CHECK(nth=0 OR nth>=2),
  target_kind VARCHAR(8) NOT NULL CHECK(target_kind IN ('PRODUCT','CATEGORY')),
  product_id VARCHAR(36) REFERENCES products(id),
  category VARCHAR(40),
  branch_id VARCHAR(36) REFERENCES branches(id),
  starts_at BIGINT,
  ends_at BIGINT,
  active BOOLEAN NOT NULL,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  CHECK((target_kind='PRODUCT' AND product_id IS NOT NULL AND category IS NULL)
     OR (target_kind='CATEGORY' AND category IS NOT NULL AND product_id IS NULL)),
  CHECK((kind='ITEM_PERCENT' AND nth=0 AND percent<=90)
     OR (kind='NTH_PERCENT' AND nth>=2))
);

CREATE INDEX idx_item_promotions_active ON item_promotions(active, branch_id);

CREATE TABLE order_item_promotions(
  order_id VARCHAR(20) PRIMARY KEY REFERENCES orders(id),
  promotion_id VARCHAR(36) NOT NULL,
  name VARCHAR(40) NOT NULL,
  kind VARCHAR(12) NOT NULL,
  percent INTEGER NOT NULL,
  nth INTEGER NOT NULL,
  target_kind VARCHAR(8) NOT NULL,
  target_id VARCHAR(40) NOT NULL,
  discounted_units INTEGER NOT NULL CHECK(discounted_units>0),
  discount_amount INTEGER NOT NULL CHECK(discount_amount>0),
  created_at BIGINT NOT NULL
);

ALTER TABLE orders ADD COLUMN item_discount_amount INTEGER NOT NULL DEFAULT 0;
ALTER TABLE order_items ADD COLUMN discount_amount INTEGER NOT NULL DEFAULT 0;
