-- =============================================================
-- 第 7 组 · 第十二周任务 A2：审计日志增量脚本
-- 新增 sys_login_log（登录日志）/ sys_operation_log（操作日志）两张表。
-- 独立文件增量添加：不改 02_tables.sql，避免与组长/其他成员合并冲突。
-- 老师点名："登录不同接口地址不一样" → 两表都记 uri 字段。
-- =============================================================

USE scenic_tour;

-- 登录日志：成功和失败都记——审计要能看出爆破尝试（连续失败 = 暴力破解信号）
CREATE TABLE IF NOT EXISTS sys_login_log (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '登录日志标识',
    username     VARCHAR(64)  NOT NULL COMMENT '尝试登录的用户名（失败时也可能是瞎猜的）',
    user_id      BIGINT       NULL COMMENT '登录用户标识（失败/账号不存在时 NULL，逻辑外键 -> sys_user.id）',
    ip           VARCHAR(64)  NULL COMMENT '来源 IP（X-Forwarded-For 首段或 remoteAddr）',
    uri          VARCHAR(255) NOT NULL COMMENT '登录接口地址——不同登录方式 URI 不同（老师点名）',
    success      TINYINT      NOT NULL COMMENT '是否成功（1 成功 / 0 失败）',
    fail_reason  VARCHAR(128) NULL COMMENT '失败原因（验证码错/密码错/被限流，成功时 NULL）',
    user_agent   VARCHAR(500) NULL COMMENT '客户端 UA（审计溯源用）',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '尝试时间',
    PRIMARY KEY (id),
    KEY idx_created_at (created_at),
    KEY idx_username_created (username, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '登录日志表（成功+失败全记，审计爆破）';

-- 操作日志：AOP 切面自动记录，不手埋（注解 @OperationLog）
CREATE TABLE IF NOT EXISTS sys_operation_log (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '操作日志标识',
    user_id      BIGINT       NULL COMMENT '操作人标识（游客操作可 NULL）',
    username     VARCHAR(64)  NULL COMMENT '操作人用户名冗余快照（用户改名后仍能追溯）',
    action       VARCHAR(64)  NOT NULL COMMENT '动作码（like/favorite/qa:ask/file:upload...）',
    target_type  VARCHAR(64)  NULL COMMENT '目标类型（spot/qa/file/knowledge_doc）',
    target_id    VARCHAR(64)  NULL COMMENT '目标标识（字符串兼容 uuid 与数字 ID）',
    detail       VARCHAR(1000) NULL COMMENT '操作详情（JSON 或简述，不入敏感字段）',
    uri          VARCHAR(255) NOT NULL COMMENT '请求 URI',
    method       VARCHAR(10)  NOT NULL COMMENT 'HTTP 方法',
    ip           VARCHAR(64)  NULL COMMENT '来源 IP',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    PRIMARY KEY (id),
    KEY idx_created_at (created_at),
    KEY idx_action_created (action, created_at),
    KEY idx_user_created (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '操作日志表（AOP 切面自动埋点）';

-- 审计页面菜单权限（复用已有 menu:admin 父节点 perm_id=1）
INSERT INTO sys_permission (perm_id, perm_code, perm_type, perm_name, url, parent_id, sort, status) VALUES
    (11, 'log:audit', 1, '日志审计', '/audit', 1, 10, 1)
ON DUPLICATE KEY UPDATE perm_name = VALUES(perm_name);

INSERT INTO sys_role_permission (role_id, perm_id)
SELECT r.id, p.perm_id FROM sys_role r JOIN sys_permission p
WHERE r.role_code = 'ADMIN' AND p.perm_code = 'log:audit'
ON DUPLICATE KEY UPDATE perm_id = VALUES(perm_id);
