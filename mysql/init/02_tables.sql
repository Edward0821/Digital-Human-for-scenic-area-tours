-- =============================================================
-- 第 7 组 · 景区导览服务 AI 数字人系统 —— 02 建表脚本（14 张）
-- 设计原则（与《需求分析文档》第 4 章数据项字典一一对应）：
--   1. 不建物理外键：引用完整性由 Service 层校验，避免约束冲突
--      返回数据库错误码难以转化为用户友好提示（课程要求）；
--   2. 关联关系以注释形式标注（逻辑外键）；
--   3. 全部 InnoDB + utf8mb4；金额/评分用 DOUBLE，禁用 FLOAT；
--   4. 全业务表带 created_at 审计字段。
-- 索引原则：仅为高频查询建索引（最左前缀可复用），区分度低的
--   字段不单独建索引，避免写放大。
-- =============================================================

USE scenic_tour;

-- -------------------------------------------------------------
-- 权限域（RBAC 一级：用户-角色）
-- -------------------------------------------------------------

-- 用户表（实体名 User，物理表避开 MySQL 保留字 user 加 sys_ 前缀）
CREATE TABLE sys_user (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户唯一标识',
    username    VARCHAR(50)  NOT NULL COMMENT '登录用户名（3~50 字符）',
    password    VARCHAR(100) NOT NULL COMMENT '登录密码（BCrypt 哈希，禁明文）',
    role        VARCHAR(20)  NOT NULL DEFAULT 'VISITOR' COMMENT '角色冗余快照（免 join 判权；变更时与 sys_user_role 同事务双写）',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci TABLESPACE ts_scenic COMMENT = '用户表';

-- 角色表
CREATE TABLE sys_role (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '角色标识',
    role_code   VARCHAR(20)  NOT NULL COMMENT '角色编码（ADMIN/VISITOR）',
    role_name   VARCHAR(50)  NOT NULL COMMENT '角色显示名',
    description VARCHAR(200) NULL COMMENT '角色说明',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (role_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci TABLESPACE ts_scenic COMMENT = '角色表（RBAC 一级）';

-- 用户-角色关联表（逻辑外键：user_id -> sys_user.id，role_id -> sys_role.id）
CREATE TABLE sys_user_role (
    id          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '关联记录标识',
    user_id     BIGINT   NOT NULL COMMENT '用户标识',
    role_id     BIGINT   NOT NULL COMMENT '角色标识',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_role (user_id, role_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci TABLESPACE ts_scenic COMMENT = '用户-角色关联表（RBAC 一级）';

-- -------------------------------------------------------------
-- 用户域
-- -------------------------------------------------------------

-- 游客档案表：仅注册后建档，与 sys_user 一对一共享主键
-- （匿名游客不建档，兴趣标签存 Redis 会话，问答历史以 session_id 关联）
CREATE TABLE visitor (
    id            BIGINT        NOT NULL COMMENT '游客档案标识（与 sys_user.id 同值，一对一）',
    user_id       BIGINT        NOT NULL COMMENT '关联 sys_user.id（一对一，逻辑外键）',
    nickname      VARCHAR(50)   NOT NULL COMMENT '游客昵称',
    interest_tags JSON          NULL COMMENT '兴趣标签集合（HISTORY/CULTURE/NATURE/FOOD/FAMILY，见 sys_dict）',
    preference    VARCHAR(200)  NULL COMMENT '偏好补充说明',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '档案创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_id (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci TABLESPACE ts_scenic COMMENT = '游客档案表（注册后建档）';

-- -------------------------------------------------------------
-- 内容域
-- -------------------------------------------------------------

-- 景点表（基础数据由 03 脚本预置，后台不提供维护页面）
CREATE TABLE scenic_spot (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '景点唯一标识',
    name        VARCHAR(100) NOT NULL COMMENT '景点名称（唯一）',
    intro       TEXT         NULL COMMENT '景点简介',
    history     TEXT         NULL COMMENT '景点历史资料',
    culture     TEXT         NULL COMMENT '景点文化资料',
    image_url   VARCHAR(500) NULL COMMENT '景点示例图地址',
    coordinates VARCHAR(50)  NULL COMMENT '景点坐标（经纬度文本）',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci TABLESPACE ts_scenic COMMENT = '景点表';

-- 知识库文档表
CREATE TABLE knowledge_doc (
    id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '文档唯一标识',
    title      VARCHAR(200) NOT NULL COMMENT '文档标题',
    content    TEXT        NOT NULL COMMENT '文档解析后的文本内容（分片来源）',
    type       VARCHAR(20) NOT NULL COMMENT '文档类型（SCRIPT/MATERIAL/FAQ，见 sys_dict）',
    status     TINYINT     NOT NULL DEFAULT 0 COMMENT '处理状态（0 待处理/1 已入库/2 失败）',
    file_id    BIGINT      NULL COMMENT '原始上传文件标识（逻辑外键 -> sys_file.id；原始文件落盘，表内只存解析文本）',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    PRIMARY KEY (id),
    KEY idx_type_status (type, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci TABLESPACE ts_scenic COMMENT = '知识库文档表';

-- 知识分片表（逻辑外键：doc_id -> knowledge_doc.id，一对多）
CREATE TABLE knowledge_chunk (
    id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '分片记录标识',
    doc_id     BIGINT      NOT NULL COMMENT '所属文档标识',
    chunk_no   INT         NOT NULL COMMENT '分片序号（文档内从 1 递增）',
    content    TEXT        NOT NULL COMMENT '分片文本内容',
    vector_id  VARCHAR(64) NULL COMMENT 'Redis 向量库分片键（BGE 向量化双写成功后写入）',
    status     TINYINT     NOT NULL DEFAULT 0 COMMENT '分片状态（0 有效/1 已清理）',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
    PRIMARY KEY (id),
    KEY idx_doc_chunk (doc_id, chunk_no),
    UNIQUE KEY uk_vector_id (vector_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci TABLESPACE ts_scenic COMMENT = '知识分片表（向量库同步明细）';

-- 推荐路线表（逻辑关联：spot_ids 元素 -> scenic_spot.id）
CREATE TABLE route (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '路线唯一标识',
    name          VARCHAR(100) NOT NULL COMMENT '路线名称',
    spot_ids      JSON         NOT NULL COMMENT '包含景点标识序列（至少 2 个，保持游览顺序）',
    suitable_tags JSON         NOT NULL COMMENT '适用兴趣标签（至少 1 个，同 interest_tags 字典）',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci TABLESPACE ts_scenic COMMENT = '推荐路线表';

-- -------------------------------------------------------------
-- 交互域
-- -------------------------------------------------------------

-- 问答记录表（匿名交互以 session_id 关联；user_id 注册后回填）
CREATE TABLE qa_record (
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '问答记录标识',
    session_id          VARCHAR(36)  NOT NULL COMMENT '会话标识（前端 UUID）',
    user_id             BIGINT       NULL COMMENT '提问用户标识（匿名 NULL，逻辑外键 -> sys_user.id）',
    question            TEXT         NOT NULL COMMENT '游客问题文本（或图片描述）',
    answer              TEXT         NOT NULL COMMENT '系统回答文本',
    interaction_type    VARCHAR(10)  NOT NULL COMMENT '交互输入类型（VOICE/TEXT/IMAGE）',
    answer_type         VARCHAR(20)  NOT NULL COMMENT '回答输出形式（TEXT/VOICE/TEXT_VOICE）',
    image_url           VARCHAR(500) NULL COMMENT '识景图片地址（仅 IMAGE 型）',
    recognized_spot_id  BIGINT       NULL COMMENT '识景命中景点（未命中 NULL，逻辑外键 -> scenic_spot.id）',
    satisfaction        VARCHAR(10)  NULL COMMENT '游客评价（GOOD/BAD，NULL 未评价；满意度=好评率 BR-10）',
    latency_ms          INT          NOT NULL COMMENT '端到端时延毫秒（阈值 5000，BR-05）',
    sentiment_score     DOUBLE       NULL COMMENT '情感得分（-1.0~1.0，NULL 未分析）',
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提问时间',
    PRIMARY KEY (id),
    KEY idx_session_created (session_id, created_at),
    KEY idx_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci TABLESPACE ts_scenic COMMENT = '问答记录表';

-- 交互日志表（全量留存，BR-06）
CREATE TABLE conversation_log (
    id               BIGINT      NOT NULL AUTO_INCREMENT COMMENT '交互日志标识',
    session_id       VARCHAR(36) NOT NULL COMMENT '会话标识（前端 UUID）',
    user_id          BIGINT      NULL COMMENT '交互用户标识（匿名 NULL）',
    interaction_type VARCHAR(10) NOT NULL COMMENT '交互类型（VOICE/TEXT/IMAGE）',
    latency_ms       INT         NOT NULL COMMENT '交互时延毫秒',
    success          TINYINT     NOT NULL DEFAULT 1 COMMENT '是否成功（1 成功/0 失败）',
    created_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '交互时间',
    PRIMARY KEY (id),
    KEY idx_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci TABLESPACE ts_scenic COMMENT = '交互日志表';

-- -------------------------------------------------------------
-- 运营域
-- -------------------------------------------------------------

-- 数字人形象表（BR-07：全表至多一条 is_active=1）
CREATE TABLE digital_human (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '形象配置标识',
    appearance  VARCHAR(50) NOT NULL COMMENT '外观方案（预设枚举，见 sys_dict）',
    outfit      VARCHAR(50) NOT NULL COMMENT '服装方案（预设枚举，见 sys_dict）',
    voice       VARCHAR(50) NOT NULL COMMENT '音色方案（Edge-TTS 预设音色）',
    is_active   TINYINT     NOT NULL DEFAULT 0 COMMENT '是否当前启用（1 启用/0 停用）',
    updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近更新时间',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci TABLESPACE ts_scenic COMMENT = '数字人形象表';

-- 感受度报告表（generated_at 即本表审计时间戳）
CREATE TABLE report (
    id                  BIGINT      NOT NULL AUTO_INCREMENT COMMENT '报告唯一标识',
    period              VARCHAR(20) NOT NULL COMMENT '统计周期类型（DAY/WEEK/MONTH）',
    period_start        DATE        NOT NULL COMMENT '周期起始日期',
    period_end          DATE        NOT NULL COMMENT '周期结束日期',
    qa_count            INT         NOT NULL COMMENT '周期内问答总数',
    satisfaction_rate   DOUBLE      NOT NULL COMMENT '好评率（好评数/已评价数，BR-10）',
    avg_latency_ms      INT         NOT NULL COMMENT '周期内平均时延',
    focus_points        TEXT        NOT NULL COMMENT '关注点分析结论',
    sentiment_trend     TEXT        NOT NULL COMMENT '情感趋势结论',
    service_suggestions TEXT        NOT NULL COMMENT '服务改进建议',
    generated_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '报告生成时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_period_start (period, period_start)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci TABLESPACE ts_scenic COMMENT = '感受度报告表';

-- -------------------------------------------------------------
-- 系统域
-- -------------------------------------------------------------

-- 通用字典表（仅承载运营可扩项；固定技术枚举留在代码常量）
CREATE TABLE sys_dict (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '字典标识',
    dict_type  VARCHAR(50)  NOT NULL COMMENT '字典类型（INTEREST_TAG/DOC_TYPE/VOICE_STYLE/DH_APPEARANCE/DH_OUTFIT）',
    code       VARCHAR(50)  NOT NULL COMMENT '类型内唯一编码',
    name       VARCHAR(100) NOT NULL COMMENT '显示名',
    sort       INT          NOT NULL DEFAULT 0 COMMENT '排序号',
    status     TINYINT      NOT NULL DEFAULT 1 COMMENT '状态（1 启用/0 停用）',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_type_code (dict_type, code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci TABLESPACE ts_scenic COMMENT = '通用字典表';

-- 文件表（知识文档原始文件元数据 + 分片上传断点续传状态）
CREATE TABLE sys_file (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '文件标识',
    file_name       VARCHAR(255) NOT NULL COMMENT '原始文件名',
    storage_path    VARCHAR(500) NOT NULL COMMENT '落盘相对路径（uploads/yyyyMM/uuid.ext）',
    file_size       BIGINT       NOT NULL COMMENT '文件字节数（> 0）',
    file_type       VARCHAR(20)  NOT NULL COMMENT '类型白名单（pdf/txt/md/docx）',
    md5             VARCHAR(32)  NOT NULL COMMENT '文件 MD5（秒传判断与完整性校验）',
    chunk_size      BIGINT       NOT NULL COMMENT '分片大小（默认 5MB）',
    chunk_total     INT          NOT NULL COMMENT '分片总数',
    uploaded_chunks JSON         NOT NULL COMMENT '已收分片号数组（断点续传依据，续传只补缺失片）',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '状态（0 上传中/1 已完成/2 已失败）',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_md5 (md5)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci TABLESPACE ts_scenic COMMENT = '文件表（分片上传/断点续传）';
