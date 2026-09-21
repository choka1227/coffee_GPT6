-- 游標分頁在 GLOBAL 資料範圍下的排序鍵
CREATE INDEX idx_orders_created_id ON orders(created_at, id);
