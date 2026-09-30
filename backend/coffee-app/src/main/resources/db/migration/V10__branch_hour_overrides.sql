CREATE TABLE branch_day_overrides(
  branch_id VARCHAR(36) NOT NULL REFERENCES branches(id),
  on_date INTEGER NOT NULL,
  closed BOOLEAN NOT NULL,
  note VARCHAR(40) NOT NULL DEFAULT '',
  updated_at BIGINT NOT NULL,
  updated_by VARCHAR(36) NOT NULL REFERENCES accounts(id),
  PRIMARY KEY(branch_id,on_date)
);

CREATE TABLE branch_day_override_hours(
  id VARCHAR(36) PRIMARY KEY,
  branch_id VARCHAR(36) NOT NULL,
  on_date INTEGER NOT NULL,
  open_minute INTEGER NOT NULL CHECK(open_minute BETWEEN 0 AND 1439),
  close_minute INTEGER NOT NULL CHECK(close_minute BETWEEN 1 AND 1440),
  UNIQUE(branch_id,on_date,open_minute),
  FOREIGN KEY(branch_id,on_date)
    REFERENCES branch_day_overrides(branch_id,on_date) ON DELETE CASCADE
);

CREATE INDEX idx_branch_day_overrides_date ON branch_day_overrides(on_date);
