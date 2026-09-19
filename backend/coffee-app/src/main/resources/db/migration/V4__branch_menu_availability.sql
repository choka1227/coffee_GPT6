CREATE TABLE branch_products(
  branch_id VARCHAR(36) NOT NULL REFERENCES branches(id),
  product_id VARCHAR(36) NOT NULL REFERENCES products(id),
  availability VARCHAR(12) NOT NULL CHECK(availability IN ('AVAILABLE','SOLD_OUT','UNLISTED')),
  sold_out_date INTEGER,
  updated_at BIGINT NOT NULL,
  updated_by VARCHAR(36) NOT NULL REFERENCES accounts(id),
  PRIMARY KEY(branch_id,product_id),
  CHECK((availability='SOLD_OUT' AND sold_out_date IS NOT NULL)
     OR (availability<>'SOLD_OUT' AND sold_out_date IS NULL))
);

CREATE INDEX idx_branch_products_branch ON branch_products(branch_id,availability);

INSERT INTO role_permissions(role_code,permission)
  SELECT 'MANAGER','MENU_AVAILABILITY'
  WHERE EXISTS(SELECT 1 FROM roles WHERE code='MANAGER')
    AND NOT EXISTS(SELECT 1 FROM role_permissions
                   WHERE role_code='MANAGER' AND permission='MENU_AVAILABILITY');

INSERT INTO role_permissions(role_code,permission)
  SELECT 'CASHIER','MENU_AVAILABILITY'
  WHERE EXISTS(SELECT 1 FROM roles WHERE code='CASHIER')
    AND NOT EXISTS(SELECT 1 FROM role_permissions
                   WHERE role_code='CASHIER' AND permission='MENU_AVAILABILITY');

INSERT INTO role_permissions(role_code,permission)
  SELECT 'HQ','MENU_AVAILABILITY'
  WHERE EXISTS(SELECT 1 FROM roles WHERE code='HQ')
    AND NOT EXISTS(SELECT 1 FROM role_permissions
                   WHERE role_code='HQ' AND permission='MENU_AVAILABILITY');
