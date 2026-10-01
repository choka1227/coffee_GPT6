ALTER TABLE branches ADD COLUMN last_order_minutes INTEGER NOT NULL DEFAULT 0;
ALTER TABLE branches ADD CONSTRAINT ck_branches_last_order
  CHECK(last_order_minutes BETWEEN 0 AND 120);
