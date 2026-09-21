CREATE TABLE branch_hours(
  id VARCHAR(36) PRIMARY KEY,
  branch_id VARCHAR(36) NOT NULL REFERENCES branches(id),
  day_of_week SMALLINT NOT NULL CHECK(day_of_week BETWEEN 1 AND 7),
  open_minute INTEGER NOT NULL CHECK(open_minute BETWEEN 0 AND 1439),
  close_minute INTEGER NOT NULL CHECK(close_minute BETWEEN 1 AND 1440),
  UNIQUE(branch_id, day_of_week, open_minute)
);

CREATE INDEX idx_branch_hours_branch ON branch_hours(branch_id);
