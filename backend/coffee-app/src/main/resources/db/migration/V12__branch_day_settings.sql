-- NULL means that the day keeps using branches.last_order_minutes.
ALTER TABLE branch_day_overrides ADD COLUMN last_order_minutes INTEGER;
ALTER TABLE branch_day_overrides ADD CONSTRAINT ck_branch_day_overrides_last_order
  CHECK(last_order_minutes IS NULL OR last_order_minutes BETWEEN 0 AND 120);

INSERT INTO role_permissions(role_code,permission)
  SELECT 'MANAGER','BRANCH_HOURS_OVERRIDE'
  WHERE EXISTS(SELECT 1 FROM roles WHERE code='MANAGER')
    AND NOT EXISTS(SELECT 1 FROM role_permissions
                   WHERE role_code='MANAGER' AND permission='BRANCH_HOURS_OVERRIDE');

INSERT INTO role_permissions(role_code,permission)
  SELECT 'HQ','BRANCH_HOURS_OVERRIDE'
  WHERE EXISTS(SELECT 1 FROM roles WHERE code='HQ')
    AND NOT EXISTS(SELECT 1 FROM role_permissions
                   WHERE role_code='HQ' AND permission='BRANCH_HOURS_OVERRIDE');
