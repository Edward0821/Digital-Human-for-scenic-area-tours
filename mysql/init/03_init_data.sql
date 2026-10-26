-- =============================================================
-- 第 7 组 · 景区导览服务 AI 数字人系统 —— 03 初始化数据
-- 原则（课程要求）：没有维护页面的数据必须在此预置初始值。
-- 本脚本幂等性说明：首次容器初始化时库为空，直接插入；
-- 手动重复执行前请确认数据未存在（或仅用于全新库）。
-- =============================================================

USE scenic_tour;

-- -------------------------------------------------------------
-- RBAC：角色 + 管理员账号 + 绑定（BR-01：管理员由初始化脚本预置）
-- -------------------------------------------------------------

INSERT IGNORE INTO sys_role (id, role_code, role_name, description) VALUES
    (1, 'ADMIN',   '管理员', '拥有管理后台全部功能权限'),
    (2, 'VISITOR', '游客',   '游客端功能权限（问答/识景/推荐）');

-- admin 初始密码为 password（下方哈希的明文），仅演示用。
-- 正式演示前请用项目内 BCryptPasswordEncoder 生成新哈希替换：
--   new BCryptPasswordEncoder().encode("你定的密码")
INSERT IGNORE INTO sys_user (id, username, password, role) VALUES
    (1, 'admin', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'ADMIN');

INSERT IGNORE INTO sys_user_role (user_id, role_id) VALUES
    (1, 1);

-- -------------------------------------------------------------
-- 字典（运营可扩项；无维护页面，必须预置初始值）
-- -------------------------------------------------------------

INSERT IGNORE INTO sys_dict (dict_type, code, name, sort) VALUES
    ('INTEREST_TAG', 'HISTORY', '历史',  1),
    ('INTEREST_TAG', 'CULTURE', '文化',  2),
    ('INTEREST_TAG', 'NATURE',  '自然风光', 3),
    ('INTEREST_TAG', 'FOOD',    '美食',  4),
    ('INTEREST_TAG', 'FAMILY',  '亲子',  5),
    ('DOC_TYPE',     'SCRIPT',  '讲解词', 1),
    ('DOC_TYPE',     'MATERIAL','文史资料', 2),
    ('DOC_TYPE',     'FAQ',     '常见问题', 3),
    ('DH_APPEARANCE','CLASSIC', '经典形象', 1),
    ('DH_APPEARANCE','LIVELY',  '活泼形象', 2),
    ('DH_OUTFIT',    'GUIDE',   '讲解员正装', 1),
    ('DH_OUTFIT',    'LEISURE', '休闲装', 2),
    ('DH_OUTFIT',    'TRADITIONAL', '传统服饰', 3),
    ('VOICE_STYLE',  'zh-CN-XiaoxiaoNeural',  '晓晓（温柔女声）', 1),
    ('VOICE_STYLE',  'zh-CN-YunxiNeural',     '云希（亲和男声）', 2),
    ('VOICE_STYLE',  'zh-CN-YunyangNeural',   '云扬（播音男声）', 3);

-- -------------------------------------------------------------
-- 景点（后台无维护页面，脚本预置；以下为示例，
-- 景区语料定稿后替换为真实景点数据）
-- -------------------------------------------------------------

INSERT IGNORE INTO scenic_spot (id, name, intro, history, culture) VALUES
    (1, '东门游客中心', '景区主入口服务区，提供导览咨询与休憩服务。', NULL, NULL),
    (2, '湖心亭',       '湖面中心的观景亭，可环视全湖风光。',         NULL, NULL),
    (3, '古戏台',       '保存完好的传统戏台，定期举办民俗演出。',     NULL, NULL),
    (4, '观景长廊',     '沿山势修建的长廊，可远眺景区全貌。',         NULL, NULL);

-- -------------------------------------------------------------
-- 推荐路线（含默认兜底路线，用例 3-4 异常流）
-- -------------------------------------------------------------

INSERT IGNORE INTO route (id, name, spot_ids, suitable_tags) VALUES
    (1, '经典环线一日游', '[1, 2, 3, 4]', '["HISTORY", "NATURE", "CULTURE"]');

-- -------------------------------------------------------------
-- 数字人形象（BR-07：预设 2 套，同一时刻仅 1 套启用）
-- -------------------------------------------------------------

INSERT IGNORE INTO digital_human (id, appearance, outfit, voice, is_active) VALUES
    (1, 'CLASSIC', 'GUIDE', 'zh-CN-XiaoxiaoNeural', 1),
    (2, 'LIVELY',  'LEISURE', 'zh-CN-YunxiNeural', 0);
