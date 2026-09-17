CREATE TABLE option_groups(
  id VARCHAR(36) PRIMARY KEY,
  name VARCHAR(40) NOT NULL,
  selection VARCHAR(8) NOT NULL CHECK(selection IN ('SINGLE','MULTI')),
  min_select INTEGER NOT NULL CHECK(min_select>=0),
  max_select INTEGER NOT NULL CHECK(max_select>=1),
  active BOOLEAN NOT NULL,
  sort_order INTEGER NOT NULL DEFAULT 99,
  CHECK(min_select<=max_select)
);

CREATE TABLE option_items(
  id VARCHAR(36) PRIMARY KEY,
  group_id VARCHAR(36) NOT NULL REFERENCES option_groups(id),
  name VARCHAR(40) NOT NULL,
  price_delta INTEGER NOT NULL CHECK(price_delta>=0),
  cost_delta INTEGER NOT NULL CHECK(cost_delta>=0),
  active BOOLEAN NOT NULL,
  sort_order INTEGER NOT NULL DEFAULT 99
);
CREATE INDEX idx_option_items_group ON option_items(group_id,sort_order);

CREATE TABLE product_option_groups(
  product_id VARCHAR(36) NOT NULL REFERENCES products(id),
  group_id VARCHAR(36) NOT NULL REFERENCES option_groups(id),
  sort_order INTEGER NOT NULL DEFAULT 99,
  PRIMARY KEY(product_id,group_id)
);

CREATE TABLE order_item_options(
  id VARCHAR(36) PRIMARY KEY,
  order_item_id VARCHAR(36) NOT NULL REFERENCES order_items(id),
  group_id VARCHAR(36) NOT NULL,
  group_name VARCHAR(40) NOT NULL,
  option_id VARCHAR(36) NOT NULL,
  option_name VARCHAR(40) NOT NULL,
  price_delta INTEGER NOT NULL,
  cost_delta INTEGER NOT NULL
);
CREATE INDEX idx_order_item_options_item ON order_item_options(order_item_id);

ALTER TABLE order_items ADD COLUMN options_price INTEGER NOT NULL DEFAULT 0;
ALTER TABLE order_items ADD COLUMN options_cost INTEGER NOT NULL DEFAULT 0;
ALTER TABLE order_items ALTER COLUMN temperature DROP NOT NULL;
ALTER TABLE order_items ALTER COLUMN sugar DROP NOT NULL;

INSERT INTO option_groups(id,name,selection,min_select,max_select,active,sort_order) VALUES
  ('temperature','溫度','SINGLE',1,1,true,1),
  ('sugar','甜度','SINGLE',1,1,true,2);

INSERT INTO option_items(id,group_id,name,price_delta,cost_delta,active,sort_order) VALUES
  ('temp-hot','temperature','熱',0,0,true,1),
  ('temp-normal','temperature','正常冰',0,0,true,2),
  ('temp-less','temperature','少冰',0,0,true,3),
  ('temp-none','temperature','去冰',0,0,true,4),
  ('sugar-none','sugar','無糖',0,0,true,1),
  ('sugar-light','sugar','微糖',0,0,true,2),
  ('sugar-half','sugar','半糖',0,0,true,3),
  ('sugar-full','sugar','正常糖',0,0,true,4);

-- 既有規則：非「手作烘焙」的商品才有溫度與甜度
-- 注意：這一段只對「執行 V3 當下已存在的商品」生效，見 4.3 節
INSERT INTO product_option_groups(product_id,group_id,sort_order)
  SELECT id,'temperature',1 FROM products WHERE category <> '手作烘焙'
  UNION ALL
  SELECT id,'sugar',2 FROM products WHERE category <> '手作烘焙';
