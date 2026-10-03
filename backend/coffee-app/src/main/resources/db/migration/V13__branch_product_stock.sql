CREATE TABLE branch_product_stock(
  branch_id  VARCHAR(36) NOT NULL REFERENCES branches(id),
  product_id VARCHAR(36) NOT NULL REFERENCES products(id),
  on_date    INTEGER NOT NULL,
  quantity   INTEGER NOT NULL CHECK(quantity  >= 0 AND quantity  <= 9999),
  remaining  INTEGER NOT NULL CHECK(remaining >= 0 AND remaining <= 9999),
  updated_at BIGINT NOT NULL,
  updated_by VARCHAR(36) NOT NULL REFERENCES accounts(id),
  PRIMARY KEY(branch_id,product_id,on_date),
  CHECK(remaining <= quantity)
);

CREATE INDEX idx_branch_product_stock_date ON branch_product_stock(branch_id,on_date);

CREATE TABLE branch_product_stock_reservation(
  order_id   VARCHAR(36) NOT NULL,
  product_id VARCHAR(36) NOT NULL REFERENCES products(id),
  branch_id  VARCHAR(36) NOT NULL REFERENCES branches(id),
  on_date    INTEGER NOT NULL,
  quantity   INTEGER NOT NULL CHECK(quantity > 0),
  created_at BIGINT NOT NULL,
  PRIMARY KEY(order_id,product_id)
);

CREATE INDEX idx_bps_reservation_row
  ON branch_product_stock_reservation(branch_id,product_id,on_date);
