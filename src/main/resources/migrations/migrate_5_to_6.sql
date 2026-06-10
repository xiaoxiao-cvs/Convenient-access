-- 迁移脚本: 版本 5 到版本 6
-- 白名单增加联系 QQ 列 (问卷审核加白时带入, 可空)。
-- SQLite ALTER TABLE ADD COLUMN 对已存在的 whitelist 表追加列; 新库由 schema/whitelist.sql 直接含此列。

ALTER TABLE whitelist ADD COLUMN qq VARCHAR(20);
