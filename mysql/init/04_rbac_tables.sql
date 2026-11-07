-- =============================================================
-- 第 7 组 · 04 RBAC 权限表（第九周任务增量）
-- 内容：权限资源表 + 角色-权限关联表 + sys_user 补 email 列 + 种子数据
-- 依据：第九周课件 slide14（RBAC 用户→角色→权限多对多）
--       老师口径：权限资源=URL，粒度控制到菜单+按钮（数据级不做）
-- 执行方式：mysql -u root -p < mysql/init/04_rbac_tables.sql
--          Docker 场景随 init 目录按字典序自动执行
-- 幂等：新表 DROP IF EXISTS 重建（开发期无生产数据）；email 列用
--       information_schema 判断，重复执行安全
-- =============================================================

-- 1. 权限资源表（菜单 / 按钮两级粒度）
--    perm_code 即鉴权注解里的权限码（如 qa:ask、menu:admin），
--    url 记录权限关联的接口路径——呼应禅道"权限资源里存的一定有 URL"
DROP TABLE IF EXISTS sys_role_permission;
DROP TABLE IF EXISTS sys_permission;
CREATE TABLE sys_permission (
    perm_id    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '权限ID',
    perm_code  VARCHAR(64)  NOT NULL COMMENT '权限码（鉴权注解使用，如 kb:list、menu:admin）',
    perm_type  TINYINT      NOT NULL DEFAULT 1 COMMENT '粒度：1=菜单 2=按钮',
    perm_name  VARCHAR(64)  NOT NULL COMMENT '权限显示名',
    url        VARCHAR(200) NULL COMMENT '关联接口路径（权限资源=URL，参照禅道口径）',
    parent_id  BIGINT       NOT NULL DEFAULT 0 COMMENT '父权限ID（0=顶级菜单）',
    sort       INT          NOT NULL DEFAULT 0 COMMENT '展示排序',
    status     TINYINT      NOT NULL DEFAULT 1 COMMENT '1=启用 2=停用',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (perm_id),
    UNIQUE KEY uk_perm_code (perm_code),
    KEY idx_perm_type (perm_type, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci TABLESPACE ts_scenic COMMENT = '权限资源表（菜单/按钮两级，RBAC 二级）';

-- 2. 角色-权限关联表（RBAC 二级：角色 ↔ 权限 多对多）
CREATE TABLE sys_role_permission (
    id         BIGINT   NOT NULL AUTO_INCREMENT COMMENT '关联记录标识',
    role_id    BIGINT   NOT NULL COMMENT '角色标识（sys_role.id）',
    perm_id    BIGINT   NOT NULL COMMENT '权限标识（sys_permission.perm_id）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_perm (role_id, perm_id),
    KEY idx_perm_id (perm_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci TABLESPACE ts_scenic COMMENT = '角色-权限关联表（RBAC 二级）';

-- 3. sys_user 补 email 列（邮箱验证码登录 / 忘记密码）
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = 'scenic_tour' AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'email');
SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE sys_user ADD COLUMN email VARCHAR(100) NULL COMMENT ''邮箱（验证码登录/找回密码）'', ADD UNIQUE KEY uk_email (email)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4. 种子数据：权限资源（菜单级 + 按钮级，覆盖全部管理端与游客端核心接口）
INSERT INTO sys_permission (perm_id, perm_code, perm_type, perm_name, url, parent_id, sort, status) VALUES
    (1,  'menu:admin',    1, '管理后台菜单',   '/admin',                    0, 1,  1),
    (2,  'kb:list',       2, '知识库列表',     'GET /knowledge-docs',       1, 1,  1),
    (3,  'kb:create',     2, '创建文档',       'POST /knowledge-docs',      1, 2,  1),
    (4,  'kb:publish',    2, '发布文档',       'POST /knowledge-docs/*/publish', 1, 3, 1),
    (5,  'kb:delete',     2, '删除文档',       'DELETE /knowledge-docs/*',  1, 4,  1),
    (6,  'kb:chunks',     2, '分片明细',       'GET /knowledge-docs/*/chunks', 1, 5, 1),
    (7,  'file:manage',   2, '文件管理',       '/files/*',                  1, 6,  1),
    (8,  'dh:manage',     2, '数字人形象管理', '/digital-humans*',          1, 7,  1),
    (9,  'rpt:view',      2, '报告与大屏',     '/reports* /dashboard/*',    1, 8,  1),
    (10, 'perm:manage',   2, '权限管理',       '/permissions* /users/*',    1, 9,  1)
ON DUPLICATE KEY UPDATE perm_name = VALUES(perm_name);

-- 5. ADMIN 角色拥有全部权限（VISITOR 走游客接口，无需权限行）
INSERT INTO sys_role_permission (role_id, perm_id)
SELECT r.id, p.perm_id FROM sys_role r JOIN sys_permission p
WHERE r.role_code = 'ADMIN'
ON DUPLICATE KEY UPDATE perm_id = VALUES(perm_id);
