-- =============================================================
-- 第 7 组 · 景区导览服务 AI 数字人系统 —— 01 建库脚本
-- 内容：建数据库实例 + 建表空间 + 建用户并授权（老师要求三件套）
-- 执行方式一（Docker 部署，推荐）：
--   deploy/docker-compose.yml 已挂载 mysql/init 为
--   /docker-entrypoint-initdb.d，容器首次初始化时按文件名
--   字典序自动执行 01/02/03。Docker 场景库与账号已由环境变量
--   （MYSQL_DATABASE / MYSQL_USER / MYSQL_PASSWORD）先建，
--   本脚本 IF NOT EXISTS 幂等兜底；docker compose down -v 清除
--   数据卷后表空间文件随卷删除，重复初始化无副作用。
-- 执行方式二（本机手动，无 Docker）：
--   mysql -u root -p < mysql/init/01_create_db.sql
--   02_tables.sql、03_init_data.sql 依次执行。
--   注意：表空间无 IF NOT EXISTS，重复执行 01 前先执行
--   DROP TABLESPACE ts_scenic;
-- 任何机器上三段脚本按序执行即可完整还原数据库。
-- =============================================================

-- 1. 建数据库实例（scenic_tour，utf8mb4）
CREATE DATABASE IF NOT EXISTS scenic_tour
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

-- 2. 建表空间（通用表空间，独立数据文件 scenic_ts.ibd，位于数据目录下）
--    02_tables.sql 中 14 张表均挂载至该表空间（TABLESPACE ts_scenic）
CREATE TABLESPACE ts_scenic ADD DATAFILE 'scenic_ts.ibd' ENGINE = InnoDB;

-- 3. 建用户并授权（应用最小权限，仅 DML，不授 DDL）
--    Docker 场景：scenic_app 已由 MYSQL_USER/MYSQL_PASSWORD 环境变量先行创建，
--    此处 IF NOT EXISTS 跳过、密码以环境变量为准；GRANT 幂等重授无害。
--    手动场景：默认密码 scenic_app_2026（课程演示用，生产环境务必更换）。
CREATE USER IF NOT EXISTS 'scenic_app'@'%' IDENTIFIED BY 'scenic_app_2026';
GRANT SELECT, INSERT, UPDATE, DELETE ON scenic_tour.* TO 'scenic_app'@'%';
FLUSH PRIVILEGES;
