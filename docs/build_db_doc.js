// 第 7 组 · 数据库设计说明书生成脚本（按老师模板《数据库设计模板.doc》结构）
// 运行：node build_db_doc.js
// 输出：第7组-景区导览服务AI数字人系统数据库设计说明书.docx
const fs = require("fs");
const {
  Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell,
  AlignmentType, BorderStyle, WidthType, ShadingType, HeadingLevel, LevelFormat, ImageRun,
  TableOfContents, PageBreak,
} = require("docx");

const FONT = "Microsoft YaHei";

// ---------- 基础构件 ----------
const p = (text, opts = {}) =>
  new Paragraph({
    spacing: { after: 120 },
    children: [new TextRun({ text, bold: opts.bold, size: opts.size || 22 })],
  });

const h1 = (text) => new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun(text)] });
const h2 = (text) => new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun(text)] });
const h3 = (text) => new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun(text)] });

const cell = (text, { bold = false, fill = null, width } = {}) =>
  new TableCell({
    width: { size: width, type: WidthType.DXA },
    shading: fill ? { fill, type: ShadingType.CLEAR } : undefined,
    margins: { top: 60, bottom: 60, left: 100, right: 100 },
    children: [new Paragraph({ children: [new TextRun({ text, bold, size: 20 })] })],
  });

const border = { style: BorderStyle.SINGLE, size: 1, color: "999999" };
const borders = { top: border, bottom: border, left: border, right: border };

// 通用表格：header 表头数组，rows 数据行数组，widths 列宽数组
const genTable = (header, rows, widths) =>
  new Table({
    width: { size: widths.reduce((a, b) => a + b, 0), type: WidthType.DXA },
    columnWidths: widths,
    rows: [
      new TableRow({
        cantSplit: true,
        tableHeader: true,
        children: header.map((t, i) => cell(t, { bold: true, fill: "D9E2F3", width: widths[i] })),
      }),
      ...rows.map(
        (r) =>
          new TableRow({
            cantSplit: true,
            children: r.map((t, i) => cell(String(t), { width: widths[i] })),
          })
      ),
    ],
  });

const tableCaption = (text) =>
  new Paragraph({
    alignment: AlignmentType.CENTER,
    spacing: { before: 120, after: 80 },
    children: [new TextRun({ text, bold: true, size: 20 })],
  });

// ---------- 14 张表明细数据（与 mysql/init/02_tables.sql 一一对应） ----------
// 列格式：[列名, 数据类型, 长度, 为空(是/否), 主键(是/—), 注释]
const tables = [
  { cn: "用户表", en: "sys_user", cols: [
    ["id", "BIGINT", "—", "否", "是", "用户唯一标识，自增，> 0"],
    ["username", "VARCHAR", "50", "否", "—", "登录用户名（3~50 字符），全局唯一"],
    ["password", "VARCHAR", "100", "否", "—", "登录密码，BCrypt 哈希存储，禁明文"],
    ["role", "VARCHAR", "20", "否", "—", "角色冗余快照，默认 VISITOR（判权免 join；变更时与 sys_user_role 同事务双写）"],
    ["created_at", "DATETIME", "—", "否", "—", "注册时间，默认当前时间"],
  ]},
  { cn: "角色表", en: "sys_role", cols: [
    ["id", "BIGINT", "—", "否", "是", "角色标识，自增"],
    ["role_code", "VARCHAR", "20", "否", "—", "角色编码，唯一（ADMIN / VISITOR）"],
    ["role_name", "VARCHAR", "50", "否", "—", "角色显示名"],
    ["description", "VARCHAR", "200", "是", "—", "角色说明"],
    ["created_at", "DATETIME", "—", "否", "—", "创建时间，默认当前时间"],
  ]},
  { cn: "用户-角色关联表", en: "sys_user_role", cols: [
    ["id", "BIGINT", "—", "否", "是", "关联记录标识，自增"],
    ["user_id", "BIGINT", "—", "否", "—", "用户标识，逻辑外键 → sys_user.id"],
    ["role_id", "BIGINT", "—", "否", "—", "角色标识，逻辑外键 → sys_role.id"],
    ["created_at", "DATETIME", "—", "否", "—", "创建时间，默认当前时间"],
  ], note: "(user_id, role_id) 建唯一索引，一个用户对同一角色仅绑定一次。"},
  { cn: "游客档案表", en: "visitor", cols: [
    ["id", "BIGINT", "—", "否", "是", "游客档案标识，与 sys_user.id 同值（一对一共享主键）"],
    ["user_id", "BIGINT", "—", "否", "—", "关联 sys_user.id，唯一（一对一）"],
    ["nickname", "VARCHAR", "50", "否", "—", "游客昵称"],
    ["interest_tags", "JSON", "—", "是", "—", "兴趣标签集合，取值见字典 INTEREST_TAG；匿名时存前端本地，注册后落库"],
    ["preference", "VARCHAR", "200", "是", "—", "偏好补充说明"],
    ["created_at", "DATETIME", "—", "否", "—", "档案创建时间，默认当前时间"],
  ], note: "仅注册后建档；匿名游客不建档，兴趣标签存 Redis 会话，问答历史以 session_id 关联。实现注意：visitor 与 sys_user 共享主键，两表插入必须处于同一事务——先插 sys_user 取自增 id，再以同值插 visitor，禁止两次独立提交。"},
  { cn: "景点表", en: "scenic_spot", cols: [
    ["id", "BIGINT", "—", "否", "是", "景点唯一标识，自增"],
    ["name", "VARCHAR", "100", "否", "—", "景点名称，唯一"],
    ["intro", "TEXT", "—", "是", "—", "景点简介"],
    ["history", "TEXT", "—", "是", "—", "景点历史资料"],
    ["culture", "TEXT", "—", "是", "—", "景点文化资料"],
    ["image_url", "VARCHAR", "500", "是", "—", "景点示例图地址"],
    ["coordinates", "VARCHAR", "50", "是", "—", "景点坐标（经纬度文本）"],
    ["created_at", "DATETIME", "—", "否", "—", "创建时间，默认当前时间（初始化脚本预置）"],
  ]},
  { cn: "知识库文档表", en: "knowledge_doc", cols: [
    ["id", "BIGINT", "—", "否", "是", "文档唯一标识，自增"],
    ["title", "VARCHAR", "200", "否", "—", "文档标题"],
    ["content", "TEXT", "—", "否", "—", "文档解析后的文本内容，为分片的来源"],
    ["type", "VARCHAR", "20", "否", "—", "文档类型（SCRIPT 讲解词 / MATERIAL 文史资料 / FAQ 常见问题）"],
    ["status", "TINYINT", "—", "否", "—", "处理状态，默认 0（0 待处理 / 1 已入库 / 2 失败）"],
    ["file_id", "BIGINT", "—", "是", "—", "原始上传文件标识，逻辑外键 → sys_file.id（原始文件落盘，表内只存解析文本）"],
    ["created_at", "DATETIME", "—", "否", "—", "上传时间，默认当前时间"],
  ]},
  { cn: "知识分片表", en: "knowledge_chunk", cols: [
    ["id", "BIGINT", "—", "否", "是", "分片记录标识，自增"],
    ["doc_id", "BIGINT", "—", "否", "—", "所属文档标识，逻辑外键 → knowledge_doc.id（一对多）"],
    ["chunk_no", "INT", "—", "否", "—", "分片序号，文档内从 1 递增"],
    ["content", "TEXT", "—", "否", "—", "分片文本内容"],
    ["vector_id", "VARCHAR", "64", "是", "—", "Redis 向量库分片键，BGE 向量化双写成功后写入，唯一"],
    ["status", "TINYINT", "—", "否", "—", "分片状态，默认 0（0 有效 / 1 已清理）"],
    ["created_at", "DATETIME", "—", "否", "—", "入库时间，默认当前时间"],
  ]},
  { cn: "推荐路线表", en: "route", cols: [
    ["id", "BIGINT", "—", "否", "是", "路线唯一标识，自增"],
    ["name", "VARCHAR", "100", "否", "—", "路线名称"],
    ["spot_ids", "JSON", "—", "否", "—", "包含景点标识序列，至少 2 个，保持游览顺序；元素逻辑关联 scenic_spot.id"],
    ["suitable_tags", "JSON", "—", "否", "—", "适用兴趣标签，至少 1 个，同 interest_tags 字典"],
    ["created_at", "DATETIME", "—", "否", "—", "创建时间，默认当前时间（初始化脚本预置）"],
  ]},
  { cn: "问答记录表", en: "qa_record", cols: [
    ["id", "BIGINT", "—", "否", "是", "问答记录标识，自增"],
    ["session_id", "VARCHAR", "36", "否", "—", "会话标识（前端生成的 UUID），匿名交互关联依据"],
    ["user_id", "BIGINT", "—", "是", "—", "提问用户标识，匿名提问为 NULL，逻辑外键 → sys_user.id"],
    ["question", "TEXT", "—", "否", "—", "游客问题文本（或图片描述）"],
    ["answer", "TEXT", "—", "否", "—", "系统回答文本"],
    ["interaction_type", "VARCHAR", "10", "否", "—", "交互输入类型（VOICE / TEXT / IMAGE）"],
    ["answer_type", "VARCHAR", "20", "否", "—", "回答输出形式（TEXT / VOICE / TEXT_VOICE）"],
    ["image_url", "VARCHAR", "500", "是", "—", "识景图片地址，仅 IMAGE 型记录填写"],
    ["recognized_spot_id", "BIGINT", "—", "是", "—", "识景命中景点，未命中为 NULL，逻辑外键 → scenic_spot.id"],
    ["satisfaction", "VARCHAR", "10", "是", "—", "游客评价（GOOD / BAD），NULL 表示未评价；满意度=好评率（BR-10）"],
    ["latency_ms", "INT", "—", "否", "—", "端到端时延（毫秒），阈值 5000（BR-05）"],
    ["sentiment_score", "DOUBLE", "—", "是", "—", "情感得分（-1.0 ~ 1.0），NULL 表示未分析"],
    ["created_at", "DATETIME", "—", "否", "—", "提问时间，默认当前时间"],
  ]},
  { cn: "交互日志表", en: "conversation_log", cols: [
    ["id", "BIGINT", "—", "否", "是", "交互日志标识，自增"],
    ["session_id", "VARCHAR", "36", "否", "—", "会话标识，与 qa_record.session_id 同源"],
    ["user_id", "BIGINT", "—", "是", "—", "交互用户标识，匿名为 NULL"],
    ["interaction_type", "VARCHAR", "10", "否", "—", "交互类型（VOICE / TEXT / IMAGE）"],
    ["latency_ms", "INT", "—", "否", "—", "交互时延（毫秒）"],
    ["success", "TINYINT", "—", "否", "—", "是否成功，默认 1（1 成功 / 0 失败）"],
    ["created_at", "DATETIME", "—", "否", "—", "交互时间，默认当前时间"],
  ]},
  { cn: "数字人形象表", en: "digital_human", cols: [
    ["id", "BIGINT", "—", "否", "是", "形象配置标识，自增"],
    ["appearance", "VARCHAR", "50", "否", "—", "外观方案，预设枚举（见字典 DH_APPEARANCE）"],
    ["outfit", "VARCHAR", "50", "否", "—", "服装方案，预设枚举（见字典 DH_OUTFIT）"],
    ["voice", "VARCHAR", "50", "否", "—", "音色方案，Edge-TTS 预设音色"],
    ["is_active", "TINYINT", "—", "否", "—", "是否当前启用，默认 0（1 启用 / 0 停用），全表至多一条为 1（BR-07）"],
    ["updated_at", "DATETIME", "—", "否", "—", "最近更新时间，随更新自动刷新"],
    ["created_at", "DATETIME", "—", "否", "—", "创建时间，默认当前时间"],
  ]},
  { cn: "感受度报告表", en: "report", cols: [
    ["id", "BIGINT", "—", "否", "是", "报告唯一标识，自增"],
    ["period", "VARCHAR", "20", "否", "—", "统计周期类型（DAY / WEEK / MONTH）"],
    ["period_start", "DATE", "—", "否", "—", "周期起始日期，与 period 组合唯一（同一周期仅生成一份报告）"],
    ["period_end", "DATE", "—", "否", "—", "周期结束日期，不早于 period_start"],
    ["qa_count", "INT", "—", "否", "—", "周期内问答总数"],
    ["satisfaction_rate", "DOUBLE", "—", "否", "—", "周期内好评率（好评数 / 已评价数，BR-10）"],
    ["avg_latency_ms", "INT", "—", "否", "—", "周期内平均时延"],
    ["focus_points", "TEXT", "—", "否", "—", "关注点分析结论，由问答记录聚合生成"],
    ["sentiment_trend", "TEXT", "—", "否", "—", "情感趋势结论，由情感得分聚合生成"],
    ["service_suggestions", "TEXT", "—", "否", "—", "服务改进建议，基于关注点与趋势生成"],
    ["generated_at", "DATETIME", "—", "否", "—", "报告生成时间，默认当前时间（即本表审计时间戳）"],
  ]},
  { cn: "通用字典表", en: "sys_dict", cols: [
    ["id", "BIGINT", "—", "否", "是", "字典标识，自增"],
    ["dict_type", "VARCHAR", "50", "否", "—", "字典类型（INTEREST_TAG / DOC_TYPE / VOICE_STYLE / DH_APPEARANCE / DH_OUTFIT）"],
    ["code", "VARCHAR", "50", "否", "—", "类型内唯一编码"],
    ["name", "VARCHAR", "100", "否", "—", "显示名"],
    ["sort", "INT", "—", "否", "—", "排序号，默认 0"],
    ["status", "TINYINT", "—", "否", "—", "状态，默认 1（1 启用 / 0 停用）"],
    ["created_at", "DATETIME", "—", "否", "—", "创建时间，默认当前时间"],
  ], note: "仅承载运营可扩展项（如新增兴趣标签）；固定技术枚举（如 success）留在代码常量。(dict_type, code) 建唯一索引。"},
  { cn: "文件表", en: "sys_file", cols: [
    ["id", "BIGINT", "—", "否", "是", "文件标识，自增"],
    ["file_name", "VARCHAR", "255", "否", "—", "原始文件名"],
    ["storage_path", "VARCHAR", "500", "否", "—", "落盘相对路径（uploads/yyyyMM/uuid.ext）"],
    ["file_size", "BIGINT", "—", "否", "—", "文件字节数，> 0"],
    ["file_type", "VARCHAR", "20", "否", "—", "类型白名单（pdf / txt / md / docx）"],
    ["md5", "VARCHAR", "32", "否", "—", "文件 MD5，用于秒传判断与完整性校验（普通索引）"],
    ["chunk_size", "BIGINT", "—", "否", "—", "分片大小，默认 5MB"],
    ["chunk_total", "INT", "—", "否", "—", "分片总数"],
    ["uploaded_chunks", "JSON", "—", "否", "—", "已收分片号数组，断点续传依据（续传只补缺失分片）"],
    ["status", "TINYINT", "—", "否", "—", "状态，默认 0（0 上传中 / 1 已完成 / 2 已失败）"],
    ["created_at", "DATETIME", "—", "否", "—", "创建时间，默认当前时间"],
  ]},
];

const tableSummary = [
  ["sys_user", "用户表：账号与角色冗余快照，管理员由脚本预置（BR-01）"],
  ["sys_role", "角色表：RBAC 一级角色定义"],
  ["sys_user_role", "用户-角色关联表：RBAC 一级核心关联"],
  ["visitor", "游客档案表：注册后建档，与用户一对一"],
  ["scenic_spot", "景点表：识景与推荐路线的基础数据，脚本预置"],
  ["knowledge_doc", "知识库文档表：讲解词/文史资料/FAQ 元数据与解析文本"],
  ["knowledge_chunk", "知识分片表：分片明细与 Redis 向量库同步依据（BR-04/09）"],
  ["route", "推荐路线表：景点序列与适用标签，含兜底路线"],
  ["qa_record", "问答记录表：全量留存，感受度分析与大屏数据源（BR-06）"],
  ["conversation_log", "交互日志表：全量交互留痕"],
  ["digital_human", "数字人形象表：预设外观/服装/音色与启用状态（BR-07）"],
  ["report", "感受度报告表：周期统计聚合结果"],
  ["sys_dict", "通用字典表：运营可扩展枚举（兴趣标签/文档类型/音色等）"],
  ["sys_file", "文件表：知识文档原始文件元数据与分片上传/断点续传状态"],
];

const glossary = [
  ["1", "RBAC", "基于角色的访问控制（Role-Based Access Control），通过“用户-角色”关联实现权限管理，本库采用最简单的一级结构（用户/角色/关联三表）"],
  ["2", "RAG", "检索增强生成（Retrieval-Augmented Generation），事实性问答基于本地知识库检索结果生成（BR-03）"],
  ["3", "KnowledgeChunk", "知识分片：文档切分后进入 Redis 向量库的最小检索单元，分片明细登记于 knowledge_chunk 表"],
  ["4", "session_id", "前端生成的匿名会话标识（UUID），持久化于浏览器本地，用于关联匿名游客的交互记录（BR-02）"],
  ["5", "向量库", "Redis 向量知识库：分片经 BGE 模型向量化后写入，供相似度检索"],
  ["6", "逻辑外键", "不建数据库物理外键约束，关联关系由注释标注、由 Service 层校验（避免约束冲突错误码难以转化为用户友好提示）"],
  ["7", "写放大", "一次数据写入因索引同步更新为多次物理写的现象，是精简索引设计（区分度低的字段不建索引）的依据"],
];

// ---------- 文档组装 ----------
const children = [];

children.push(new Paragraph({
  alignment: AlignmentType.CENTER, spacing: { before: 2400, after: 240 },
  children: [new TextRun({ text: "数据库设计说明书", bold: true, size: 56 })],
}));
children.push(new Paragraph({
  alignment: AlignmentType.CENTER, spacing: { after: 240 },
  children: [new TextRun({ text: "—— 景区导览服务 AI 数字人系统 ——", size: 28 })],
}));
children.push(new Paragraph({
  alignment: AlignmentType.CENTER,
  children: [new TextRun({ text: "第 7 组", size: 26 })],
}));
children.push(new Paragraph({
  alignment: AlignmentType.CENTER, spacing: { before: 120 },
  children: [new TextRun({ text: "版本 v1.0    2026 年 10 月", size: 22, color: "666666" })],
}));
children.push(new Paragraph({ children: [new PageBreak()] }));

// 目录页
children.push(new Paragraph({
  alignment: AlignmentType.CENTER, spacing: { after: 240 },
  children: [new TextRun({ text: "目  录", bold: true, size: 32 })],
}));
children.push(new TableOfContents("目录", { hyperlink: true, headingStyleRange: "1-3" }));
children.push(new Paragraph({ children: [new PageBreak()] }));

// 1 引言
children.push(h1("1  引言"));
children.push(h2("1.1  编写目的"));
children.push(p("本文档定义“景区导览服务 AI 数字人系统”的数据库设计，覆盖数据库环境、命名规则、逻辑设计（数据对象关系）与物理设计（表结构明细），并与《需求分析文档》第 4 章数据项字典一一对应，作为建库脚本（mysql/init/）、后端实体类与接口字段定义的权威依据，保证需求、设计与实现三者的字段命名与约束一致。"));
children.push(p("预期读者：第 7 组全体开发成员、课程指导教师与评阅人。"));
children.push(h2("1.2  术语表"));
children.push(tableCaption("表 1-1  术语表"));
children.push(genTable(["序号", "术语或缩略语", "说明性定义"], glossary, [900, 2200, 5926]));

// 2 数据库环境说明
children.push(h1("2  数据库环境说明"));
children.push(tableCaption("表 2-1  数据库环境"));
children.push(genTable(["项目", "说明"], [
  ["数据库系统", "MySQL 8.0（InnoDB 引擎，utf8mb4 / utf8mb4_unicode_ci 字符集）"],
  ["部署环境", "Docker 容器（服务名 scenic-mysql，镜像 mysql:8.0），数据卷持久化；初始化脚本挂载于容器 docker-entrypoint-initdb.d 目录，首次启动自动按序执行"],
  ["设计工具", "PDMaas（元数建模）：数据对象关系图绘制与表结构维护"],
  ["说明", "建库与业务账号（scenic_app，最小权限，非 root）由容器环境变量机制自动创建授权；任何机器按第 6 章脚本顺序执行即可完整还原数据库"],
  ["表空间策略", "14 张表统一挂载 InnoDB 通用表空间 ts_scenic（独立数据文件 scenic_ts.ibd，位于数据目录），由 01_create_db.sql 创建；表数据与索引集中存放于该表空间对应数据文件，随 Docker 数据卷持久化"],
], [2200, 6826]));

// 3 命名规则
children.push(h1("3  数据库的命名规则"));
children.push(p("（1）数据库名：scenic_tour，全小写。"));
children.push(p("（2）表名：小写蛇形命名。系统级表统一加 sys_ 前缀（sys_user / sys_role / sys_user_role / sys_dict / sys_file）；业务表按实体名蛇形命名（如 knowledge_doc、qa_record）。实体 User 因与 MySQL 系统表 user 冲突，物理表名调整为 sys_user。"));
children.push(p("（3）字段名：小写蛇形命名；主键统一为 id；审计时间字段统一为 created_at；状态类字段（status、is_active）取值全部枚举化并在注释中列全合法值。"));
children.push(p("（4）索引命名：唯一索引 uk_ + 字段名（如 uk_username、uk_type_code），普通索引 idx_ + 字段名（如 idx_created_at、idx_doc_chunk）；组合索引按最左前缀原则设计。"));
children.push(p("（5）字典编码：大写下划线命名（HISTORY、SCRIPT、VOICE_STYLE 等）。"));
children.push(p("（6）Redis 键（内存存储对象）：scenic:域:标识 格式，如 scenic:session:{session_id}、scenic:cache:qa:{md5}、scenic:counter:pv:{yyyyMMdd}。"));

// 4 逻辑设计
children.push(h1("4  逻辑设计"));
children.push(p("系统数据对象共 14 张表，划分为六个域：权限域（sys_user、sys_role、sys_user_role）、用户域（sys_user、visitor）、内容域（scenic_spot、knowledge_doc、knowledge_chunk、route）、交互域（qa_record、conversation_log）、运营域（digital_human、report）、系统域（sys_dict、sys_file）。"));
children.push(p("核心关系：sys_user 与 visitor 一对一（注册后建档）；knowledge_doc 与 knowledge_chunk 一对多（文档分片）；route 的 spot_ids 引用 scenic_spot.id 序列；qa_record 通过 session_id 关联匿名交互、通过 user_id 关联注册用户，并通过 recognized_spot_id 引用识景命中景点；knowledge_doc 通过 file_id 关联 sys_file（原始文件落盘）；sys_user_role 关联 sys_user 与 sys_role 实现一级 RBAC。数据对象关系图如图 4-1 所示。"));
children.push(new Paragraph({
  alignment: AlignmentType.CENTER, spacing: { before: 120, after: 80 },
  children: [new ImageRun({
    type: "jpg",
    data: fs.readFileSync(__dirname + "/assets/er-diagram.jpg"),
    transformation: { width: 580, height: 642 }, // 2257x2498 等比缩放
  })],
}));
children.push(tableCaption("图 4-1  数据对象关系图"));

// 5 物理设计
children.push(h1("5  物理设计"));
children.push(h2("5.1  表汇总"));
children.push(tableCaption("表 5-1  表汇总"));
children.push(genTable(["表名", "说明"], tableSummary, [2800, 6226]));

children.push(h2("5.2  表设计"));
tables.forEach((t, idx) => {
  children.push(h3(`表 5-${idx + 2}  ${t.en}（${t.cn}）`));
  children.push(genTable(
    ["列名", "数据类型", "长度", "为空", "主键", "注释"],
    t.cols,
    [2050, 1250, 750, 700, 750, 3526]
  ));
  if (t.note) children.push(p(`注：${t.note}`));
});

// 6 脚本文件
children.push(h1("6  脚本文件"));
children.push(h2("6.1  脚本文件清单"));
children.push(p("全部脚本位于仓库 mysql/init/ 目录，按文件名字典序执行即可在任何机器完整还原数据库："));
children.push(tableCaption("表 6-1  脚本文件清单"));
children.push(genTable(["脚本文件", "内容", "执行方式"], [
  ["01_create_db.sql", "建数据库实例（scenic_tour）+ 建表空间 ts_scenic（通用表空间，独立数据文件 scenic_ts.ibd）+ 建业务账号 scenic_app 并授权（幂等可重复执行）", "Docker 场景由容器自动执行；本机手动执行 mysql -u root -p < 01_create_db.sql"],
  ["02_tables.sql", "14 张表 DDL（含索引与注释，无物理外键）", "同上，按序自动执行"],
  ["03_init_data.sql", "初始化数据：RBAC 角色与管理员账号（BR-01）、字典初始值、预置景点、兜底推荐路线、数字人形象预设（BR-07）", "同上，按序自动执行"],
], [2400, 3800, 2826]));
children.push(p("Docker 部署场景（推荐，对应《安装部署文档》）：deploy/docker-compose.yml 已将 mysql/init 挂载为容器初始化目录，建库与业务账号授权由 MYSQL_DATABASE / MYSQL_USER / MYSQL_PASSWORD 环境变量机制完成，docker compose up 首次启动即自动完成“建库—建表—初始数据”全流程；执行 docker compose down -v 清除数据卷后再次启动可现场演示数据库一键还原。"));

// 6.2~6.4 附：脚本全文（生成时直接读取 mysql/init/ 下真实文件，保证文档与脚本同步）
const codeFont = { ascii: "Consolas", hAnsi: "Consolas", eastAsia: FONT };
const codeLine = (t) =>
  new Paragraph({
    spacing: { after: 0 },
    children: [new TextRun({ text: t.length ? t : " ", font: codeFont, size: 15 })],
  });
const scriptFiles = [
  ["6.2  附：01_create_db.sql（建库脚本全文）", "01_create_db.sql"],
  ["6.3  附：02_tables.sql（建表脚本全文）", "02_tables.sql"],
  ["6.4  附：03_init_data.sql（初始数据脚本全文）", "03_init_data.sql"],
];
for (const [title, file] of scriptFiles) {
  children.push(h2(title));
  children.push(p(`以下为 ${file} 全文，与仓库 mysql/init/${file} 保持一致（由本文档生成脚本自动读取嵌入）。`));
  const lines = fs.readFileSync(__dirname + "/../mysql/init/" + file, "utf-8").replace(/\r\n/g, "\n").split("\n");
  lines.forEach((l) => children.push(codeLine(l.replace(/\t/g, "    "))));
}

// ---------- 生成 ----------
const doc = new Document({
  creator: "第 7 组",
  title: "第7组-景区导览服务AI数字人系统数据库设计说明书",
  features: { updateFields: true },
  styles: {
    default: { document: { run: { font: { ascii: "Arial", hAnsi: "Arial", eastAsia: FONT }, size: 22 } } },
    paragraphStyles: [
      { id: "Heading1", name: "Heading 1", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { size: 30, bold: true, font: { ascii: "Arial", hAnsi: "Arial", eastAsia: FONT } },
        paragraph: { spacing: { before: 240, after: 200 }, outlineLevel: 0, keepNext: false, keepLines: false } },
      { id: "Heading2", name: "Heading 2", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { size: 26, bold: true, font: { ascii: "Arial", hAnsi: "Arial", eastAsia: FONT } },
        paragraph: { spacing: { before: 200, after: 160 }, outlineLevel: 1, keepNext: false, keepLines: false } },
      { id: "Heading3", name: "Heading 3", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { size: 23, bold: true, font: { ascii: "Arial", hAnsi: "Arial", eastAsia: FONT } },
        paragraph: { spacing: { before: 180, after: 120 }, outlineLevel: 2, keepNext: false, keepLines: false } },
    ],
  },
  sections: [{ children }],
});

Packer.toBuffer(doc).then((buf) => {
  const out = process.argv[2] || "第7组-景区导览服务AI数字人系统数据库设计说明书.docx";
  fs.writeFileSync(out, buf);
  console.log("已生成：" + out);
});
