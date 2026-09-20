const fs = require("fs");
const path = require("path");
const {
  Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell, ImageRun,
  Header, Footer, AlignmentType, LevelFormat, HeadingLevel, BorderStyle,
  WidthType, ShadingType, VerticalAlign, PageNumber, PageBreak,
} = require("C:/Users/白日梦想家/.trae-cn/work/6a65f3a321367f7fd2931c1f/node_modules/docx");

const EXPORT = "e:/javapractice/software engineering/Digital Human for scenic area tours/docs/建模图/export";
const OUT = "e:/javapractice/software engineering/Digital Human for scenic area tours/docs/第7组-景区导览服务AI数字人系统需求分析文档.docx";

const FONT = { ascii: "Arial", hAnsi: "Arial", eastAsia: "Microsoft YaHei" };
const CONTENT_W = 9026; // A4 11906 - 2*1440

// ---------- helpers ----------
function pngSize(file) {
  const buf = fs.readFileSync(file);
  return { w: buf.readUInt32BE(16), h: buf.readUInt32BE(20) };
}

function h1(text, breakBefore = true) {
  return new Paragraph({
    heading: HeadingLevel.HEADING_1,
    pageBreakBefore: breakBefore,
    children: [new TextRun(text)],
  });
}

function h2(text) {
  return new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun(text)] });
}

function h3(text) {
  return new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun(text)] });
}

function p(text, opts = {}) {
  return new Paragraph({
    alignment: AlignmentType.JUSTIFIED,
    indent: { firstLine: 480 },
    spacing: { after: 80, line: 320 },
    children: [new TextRun({ text, bold: !!opts.bold })],
  });
}

function bullet(text) {
  return new Paragraph({
    numbering: { reference: "bullets", level: 0 },
    alignment: AlignmentType.JUSTIFIED,
    spacing: { after: 60, line: 320 },
    children: [new TextRun(text)],
  });
}

function fig(file, caption) {
  const full = path.join(EXPORT, file);
  const { w, h } = pngSize(full);
  const scale = Math.min(590 / w, 620 / h);
  const tw = Math.round(w * scale);
  const th = Math.round(h * scale);
  return [
    new Paragraph({
      alignment: AlignmentType.CENTER,
      spacing: { before: 120, after: 40 },
      children: [new ImageRun({
        type: "png",
        data: fs.readFileSync(full),
        transformation: { width: tw, height: th },
        altText: { title: caption, description: caption, name: file },
      })],
    }),
    new Paragraph({
      alignment: AlignmentType.CENTER,
      spacing: { after: 160 },
      children: [new TextRun({ text: caption, size: 21, bold: true })],
    }),
  ];
}

const border = { style: BorderStyle.SINGLE, size: 1, color: "999999" };
const borders = { top: border, bottom: border, left: border, right: border };
const cellMargins = { top: 60, bottom: 60, left: 100, right: 100 };

function cell(text, width, opts = {}) {
  const lines = Array.isArray(text) ? text : [text];
  return new TableCell({
    borders,
    width: { size: width, type: WidthType.DXA },
    margins: cellMargins,
    verticalAlign: VerticalAlign.CENTER,
    shading: opts.header ? { fill: "D5E8F0", type: ShadingType.CLEAR } : undefined,
    children: lines.map(t => new Paragraph({
      alignment: opts.center ? AlignmentType.CENTER : AlignmentType.LEFT,
      spacing: { line: 260 },
      children: [new TextRun({ text: t, size: 18, bold: !!opts.header || !!opts.bold })],
    })),
  });
}

function tableCaption(text) {
  return new Paragraph({
    alignment: AlignmentType.CENTER,
    spacing: { before: 120, after: 60 },
    children: [new TextRun({ text, size: 21, bold: true })],
  });
}

// data-item dictionary table: 7 columns
const DI_COLS = [1450, 2000, 1200, 550, 1500, 1100, 1226];
function diTable(rows) {
  const header = ["字段名", "业务含义", "数据类型", "必填", "取值范围/默认值", "枚举/字典", "关联关系"];
  return new Table({
    width: { size: CONTENT_W, type: WidthType.DXA },
    columnWidths: DI_COLS,
    rows: [
      new TableRow({
        cantSplit: true,
        tableHeader: true,
        children: header.map((t, i) => cell(t, DI_COLS[i], { header: true, center: true })),
      }),
      ...rows.map(r => new TableRow({
        cantSplit: true,
        children: r.map((t, i) => cell(t, DI_COLS[i], { center: i === 3 })),
      })),
    ],
  });
}

// use-case description table: 2 columns
function ucTable(fields) {
  const cols = [1500, 7526];
  return new Table({
    width: { size: CONTENT_W, type: WidthType.DXA },
    columnWidths: cols,
    rows: fields.map(([k, v]) => new TableRow({
      cantSplit: true,
      children: [cell(k, cols[0], { bold: true, center: true }), cell(v, cols[1])],
    })),
  });
}

// generic 2-col table
function twoColTable(headerPair, rows, cols = [1800, 7226]) {
  return new Table({
    width: { size: CONTENT_W, type: WidthType.DXA },
    columnWidths: cols,
    rows: [
      new TableRow({
        cantSplit: true,
        tableHeader: true,
        children: headerPair.map((t, i) => cell(t, cols[i], { header: true, center: true })),
      }),
      ...rows.map(r => new TableRow({
        cantSplit: true,
        children: r.map((t, i) => cell(t, cols[i])),
      })),
    ],
  });
}

// ---------- document content ----------
const children = [];

// ===== cover =====
children.push(
  new Paragraph({ spacing: { before: 2800 }, children: [] }),
  new Paragraph({
    alignment: AlignmentType.CENTER,
    spacing: { after: 200 },
    children: [new TextRun({ text: "软件工程课程设计", size: 36, bold: true })],
  }),
  new Paragraph({
    alignment: AlignmentType.CENTER,
    spacing: { after: 500 },
    children: [new TextRun({ text: "需求分析文档", size: 56, bold: true })],
  }),
  new Paragraph({
    alignment: AlignmentType.CENTER,
    spacing: { after: 300 },
    children: [new TextRun({ text: "景区导览服务 AI 数字人系统", size: 32, bold: true })],
  }),
  new Paragraph({ spacing: { before: 1600 }, children: [] }),
  ...[
    "题目编号：第 6 题",
    "组　　号：第 7 组",
    "日　　期：2026 年 9 月 19 日",
  ].map(t => new Paragraph({
    alignment: AlignmentType.CENTER,
    spacing: { after: 160 },
    children: [new TextRun({ text: t, size: 26 })],
  })),
  new Paragraph({ children: [new PageBreak()] }),
);

// ===== revision history =====
children.push(
  new Paragraph({
    spacing: { before: 200, after: 120 },
    children: [new TextRun({ text: "修订历史", size: 24, bold: true })],
  }),
  tableCaption("表 0-1  文档修订历史"),
  twoColTable(["版本 / 日期", "修订说明"], [
    ["v1.0 / 2026-09-19", "初稿：含需求建模图（功能架构、用例、时序、类图、状态转换）、数据项字典、业务规则与非功能需求"],
  ], [2200, 6826]),
);

// ===== 1 引言 =====
children.push(h1("1  引言", false));
children.push(h2("1.1  编写目的"));
children.push(p("本文档是《景区导览服务 AI 数字人系统》的需求分析文档，用于明确系统的功能需求、数据需求、业务规则与非功能需求，并通过功能架构图、用例图、时序图、类图、状态转换图等需求建模图对上述需求进行可视化描述。本文档是后续系统接口设计、详细设计与编码实现的依据，也是课程评审时衡量需求理解准确性的基准材料。"));
children.push(p("预期读者：任课教师、课程评审专家、本小组全体成员。"));
children.push(h2("1.2  项目背景"));
children.push(p("随着旅游业数字化转型的推进，传统景区以人工导游与静态标识牌为主的导览方式，已难以满足游客对即时性、个性化、沉浸式游览体验的需求。本项目面向景区场景，构建一个以多模态大模型为核心的 AI 数字人导览服务系统：游客可通过语音、文字、拍照三种方式与数字人交互，获得准确的自然语言讲解与个性化游览推荐；管理方则可通过后台对知识库、数字人形象进行统一管理，并基于交互数据获得游客感受度报告与运营数据大屏。"));
children.push(p("题目要求系统至少使用一个多模态大模型作为核心 AI 能力支撑，并满足三项硬性指标：事实性问答准确率不低于 90%、语音问答端到端延迟小于 5 秒、数字人回答自然度（口型同步、语音合成、表情配合）通过专家评估。"));
children.push(h2("1.3  术语定义"));
children.push(tableCaption("表 1-1  术语与缩略语"));
children.push(twoColTable(["术语", "含义"], [
  ["AI 数字人", "以虚拟形象呈现、具备语音合成与口型/表情驱动能力的交互代理"],
  ["多模态大模型", "同时支持文本与图像输入的大语言模型，本系统采用 DeepSeek 系列模型"],
  ["RAG", "检索增强生成（Retrieval-Augmented Generation），先检索本地知识库再生成回答的技术路线"],
  ["Web Speech API", "浏览器内置的语音识别接口，本系统游客语音输入的转写引擎（Edge/Chrome 内置，无需独立部署）"],
  ["TTS", "语音合成（Text-To-Speech），将回答文本转为语音，本系统采用 Edge-TTS"],
  ["BGE", "开源中文向量 embedding 模型系列，用于将文本向量化以支持相似度检索"],
  ["向量知识库", "存储知识分片向量的 Redis Stack 向量索引，支撑 RAG 相似度检索"],
  ["口型同步", "数字人口型动作与播报语音在时间上对齐的能力（Lip-sync）"],
]));
children.push(h2("1.4  参考资料"));
children.push(bullet("课程设计题目要求：《景区导览服务 AI 数字人》"));
children.push(bullet("课程课件：《第二周 AI 驱动的需求建模——从需求获取到数据项提炼》（数据项七要素规范）"));

// ===== 2 总体描述 =====
children.push(h1("2  总体描述"));
children.push(h2("2.1  系统定位与目标"));
children.push(p("本系统定位为面向单一景区的可部署、可运营的智能导览服务，整体分为游客交互侧与管理后台侧两部分。系统建设目标如下："));
children.push(bullet("游客侧：提供语音/文字问答、拍照识景、语音讲解收听与个性化游览推荐，交互自然、回答准确；"));
children.push(bullet("管理侧：提供知识库管理、数字人形象管理、游客感受度报告与数据大屏概览四项运营能力；"));
children.push(bullet("质量目标：事实性问答准确率 ≥ 90%，语音问答端到端延迟 < 5 秒（目标 2~3 秒），数字人口型、语音、表情自然协调，系统稳定不崩溃。"));
children.push(h2("2.2  用户角色"));
children.push(p("游客：系统的服务对象。可免登录直接使用问答、识景、讲解等核心功能，匿名期间由浏览器端生成的会话标识（session_id）关联其交互记录；可选注册/登录，注册后其兴趣标签将写入游客档案跨会话持久保存，用于获得更稳定的个性化推荐。"));
children.push(p("管理员：系统的运营维护人员。通过账号密码登录管理后台（账号由数据库初始化脚本预置），进行知识库文档的上传与维护、数字人形象的配置，并查看游客感受度报告与数据大屏。"));
children.push(h2("2.3  系统范围"));
children.push(p("本期系统实现以下功能（与题目要求对齐）："));
children.push(bullet("游客交互侧：多模态交互（语音/文字输入，语音+口型/表情同步回答）、智能问答与讲解（景区历史、文化、景点特色、常见问题）、个性化游览推荐（基于兴趣标签推荐路线与讲解重点）、拍照识景（图像识别 + 自动讲解）、注册/登录（含匿名会话支持）；"));
children.push(bullet("管理后台侧：知识库管理（讲解词、文史资料、FAQ 的上传/更新/维护）、数字人形象管理（外观、服装、声音的预设配置）、游客感受度报告（关注点分析、情感趋势、服务建议）、数据大屏概览（服务人次、热门问答、满意度趋势）；"));
children.push(bullet("数据与 AI 底座：本地景区知识库 + RAG 检索增强生成，确保回答的准确性与可维护性。"));
children.push(p("以下内容明确不在本期范围内：GPS 实时定位及位置触发讲解（题目标注为可选）；数字人形象与服装的用户自由上传定制；跨景区通用化管理平台。景点信息与推荐路线等基础数据由数据库初始化脚本预置维护（课程设计范围，不提供独立管理界面，后续可扩展）。"));
children.push(h2("2.4  运行环境"));
children.push(p("客户端：现代浏览器（游客端与后台管理端均为 Web 应用，前后端分离架构），推荐 Chrome / Edge 最新两个大版本。"));
children.push(p("服务端：Spring Boot 3 应用 + MySQL（业务数据）+ Redis Stack（向量知识库与高频问答缓存），以 Docker Compose 一键部署。"));
children.push(p("AI 能力：DeepSeek 多模态大模型 API（对话生成与图像理解）、BGE 向量化服务（知识分片与查询向量化）、Edge-TTS（语音合成）；语音识别采用浏览器端 Web Speech API（Edge/Chrome 内置引擎，无需独立部署）。"));

// ===== 3 需求建模 =====
children.push(h1("3  需求建模"));
children.push(h2("3.1  系统功能架构"));
children.push(p("系统功能架构分为三层：游客交互侧、管理后台侧与 AI 能力与数据底座。游客交互侧与管理后台侧是两类用户直接可见的功能层；底座层以多模态大模型、本地知识库（RAG）、数据存储与安全认证为核心，为上层功能提供统一支撑。系统总体功能架构如图 3-1 所示。"));
children.push(...fig("fa0_zongtu.png", "图 3-1  系统功能架构总图"));
children.push(p("游客交互侧包含多模态交互、智能问答与讲解、个性化推荐、拍照识景、注册/登录五个功能模块，各模块的内部功能划分如图 3-2 所示。"));
children.push(...fig("fa1_youke.png", "图 3-2  游客交互侧功能子图"));
children.push(p("管理后台侧包含知识库管理、数字人形象管理、游客感受度报告、数据大屏概览四个功能模块，各模块的内部功能划分如图 3-3 所示。"));
children.push(...fig("fa2_houtai.png", "图 3-3  管理后台侧功能子图"));

children.push(h2("3.2  用例分析"));
children.push(p("系统参与者为游客与管理员，系统用例图如图 3-4 所示。游客可使用文字问答、语音问答（口型/表情同步）、拍照识景、收听语音讲解、个性化游览推荐及注册/登录六个用例；管理员可使用知识库管理、数字人形象管理、游客感受度报告、数据大屏概览四个用例。"));
children.push(...fig("uc0_usecase.png", "图 3-4  系统用例图"));
children.push(p("用例之间存在以下包含与扩展关系："));
children.push(bullet("文字问答、语音问答、拍照识景、个性化游览推荐均包含（include）知识库检索问答——无论通过何种通道发起，回答都必须基于本地知识库的检索结果生成，这是保证事实性准确率 ≥ 90% 的结构性约束；"));
children.push(bullet("知识库管理、数字人形象管理、游客感受度报告、数据大屏概览均包含（include）登录认证——管理端的任一操作都要求管理员先完成认证，未认证请求一律拒绝；"));
children.push(bullet("注册/登录扩展（extend）个性化游览推荐——游客匿名时即可获得基于会话标签的基础推荐，注册/登录后兴趣标签持久化，推荐结果跨会话稳定。"));
children.push(p("需要说明的是，图中两个与登录相关的用例语义不同：管理员侧的「登录认证」表达的是各管理功能的鉴权前置约束（被包含的公共用例，管理员不单独发起）；游客侧的「注册/登录」是游客主动发起的独立用例，仅通过扩展关系影响个性化推荐的标签持久化，两者互不冲突。"));

children.push(h2("3.3  核心用例描述"));
children.push(p("对四个核心用例给出结构化描述，如表 3-1 至表 3-4 所示。"));
children.push(tableCaption("表 3-1  用例：语音问答"));
children.push(ucTable([
  ["参与者", "游客"],
  ["前置条件", "游客已打开游客端页面（推荐 Edge/Chrome），数字人形象已加载"],
  ["基本流", ["（1）游客对数字人说出问题（或输入文字）；", "（2）前端调用浏览器端 ASR 引擎（Web Speech API）将语音转写为文本；", "（3）前端携带文本与会话标识（session_id）请求后端问答接口；", "（4）后端经 RAG 检索链在向量知识库中检索 TopK 知识分片；", "（5）后端组装 Prompt 上下文并流式调用大模型生成回答；", "（6）回答文本流式返回前端，同步进行 TTS 合成与口型/表情驱动；", "（7）数字人以语音 + 口型同步方式向游客播报回答；", "（8）问答记录写入数据库用于后续分析。"]],
  ["异常流", ["（1）ASR 转写失败或为空：提示游客重新输入，支持切换为文字输入；", "（2）知识库无相关内容：明确告知游客暂无该方面资料，并给出相近问题建议；", "（3）大模型或网络超时（超过 5 秒）：中断等待并给出降级提示与重试入口。"]],
  ["后置条件", "问答记录（问题、回答、交互类型、时延、满意度入口）已持久化"],
]));
children.push(tableCaption("表 3-2  用例：拍照识景"));
children.push(ucTable([
  ["参与者", "游客"],
  ["前置条件", "游客位于景区内，允许调用相机权限"],
  ["基本流", ["（1）游客拍摄景点照片并上传；", "（2）后端调用多模态大模型对图像进行识别与理解；", "（3）识别结果与本地知识库景点信息匹配；", "（4）命中景点后自动生成该景点的讲解内容；", "（5）数字人以语音 + 口型同步方式播报讲解。"]],
  ["异常流", ["（1）识别置信度低于 0.6：不强行给出讲解，引导游客改用文字/语音提问；", "（2）图像与知识库无法匹配：提示该景点暂未收录。"]],
  ["后置条件", "交互记录（图片类型、识别景点）已持久化"],
]));
children.push(tableCaption("表 3-3  用例：知识库管理"));
children.push(ucTable([
  ["参与者", "管理员"],
  ["前置条件", "管理员已通过登录认证"],
  ["基本流", ["（1）管理员在后台选择讲解词/文史资料/FAQ 文档并上传；", "（2）后端对文档执行格式解析、清洗与文本分片；", "（3）分片经 BGE 向量化后写入 Redis 向量知识库，并登记分片明细；", "（4）文档元数据保存至 MySQL，状态置为已入库；", "（5）后台刷新文档列表，提示上传成功，新知识即刻可被检索。"]],
  ["异常流", ["（1）文档格式不支持：拒绝并提示支持的格式（pdf/txt/md/docx）；", "（2）向量化入库失败：文档状态置为失败并提示重试，不影响已有知识；", "（3）重复上传同名文档：提示已存在，可选择覆盖更新（旧分片同步清理后重建）；", "（4）删除/更新文档：先按文档标识清理向量库中对应全部分片，再更新 MySQL 元数据，保持两侧一致。"]],
  ["后置条件", "知识文档元数据与向量分片明细均已入库且状态一致，可参与检索问答"],
]));
children.push(tableCaption("表 3-4  用例：个性化游览推荐"));
children.push(ucTable([
  ["参与者", "游客"],
  ["前置条件", "游客已进入推荐入口（可匿名）"],
  ["基本流", ["（1）游客选择兴趣标签（如历史、文化、自然风光）；", "（2）系统基于标签匹配推荐路线库中的候选路线，多标签命中时按命中数排序；", "（3）结合知识库检索结果确定各景点的讲解重点；", "（4）向游客展示推荐路线与对应的讲解重点。"]],
  ["扩展点", "游客注册/登录后，兴趣标签持久化保存，后续会话无需重复选择，推荐结果跨会话稳定"],
  ["异常流", ["（1）无可匹配路线：给出默认经典路线兜底。"]],
  ["后置条件", "推荐结果已展示；若已登录，兴趣标签已更新"],
]));

children.push(h2("3.4  关键业务流程时序"));
children.push(p("语音问答是系统最核心的多模态交互主线，涉及游客、前端客户端、浏览器端 ASR 引擎、后端服务、RAG 检索链、Redis 向量知识库、多模态大模型、数字人服务共 8 个参与者，时序如图 3-5 所示。整个链路采用流式管线设计：大模型流式返回、TTS 分句合成边生成边播，以满足端到端延迟小于 5 秒的指标。查询与知识分片均经 BGE 模型向量化后进行相似度检索。"));
children.push(...fig("seq0_yuyin.png", "图 3-5  语音问答时序图"));
children.push(p("知识库管理流程覆盖文档从上传到可检索的全过程，涉及管理员、后台管理前端、后端服务、文档处理服务、Redis 向量知识库与 MySQL 六个参与者，时序如图 3-6 所示。文档经解析、清洗、分片、BGE 向量化后写入向量库并登记分片明细，元数据与向量分片保持一致，保证管理端可见即可被检索。"));
children.push(...fig("seq1_zhishiku.png", "图 3-6  知识库管理时序图"));

children.push(h2("3.5  类图"));
children.push(p("系统领域模型共 10 个实体类，划分为四组：用户域（User、Visitor）、内容域（ScenicSpot、KnowledgeDoc、KnowledgeChunk、Route）、交互域（QARecord、ConversationLog）、运营域（DigitalHuman、Report）。User 与 Visitor 为继承关系（游客注册后拥有账号）；Visitor 产生 QARecord 与 ConversationLog（匿名交互通过 session_id 关联）；KnowledgeDoc 分片为 KnowledgeChunk（一对多）；ScenicSpot 关联 Route 与 KnowledgeDoc，并被拍照识景的问答记录引用；Report 基于交互记录聚合生成。类图如图 3-7 所示。"));
children.push(p("审计字段（created_at 等）在全部业务表中统一携带，为避免图面冗余，类图中仅在各实体代表位置标注，数据项字典中逐一列全。"));
children.push(...fig("class0_leitu.png", "图 3-7  系统类图"));

children.push(h2("3.6  状态转换图"));
children.push(p("以问答记录为例描述核心对象的生命周期：游客发起提问后记录进入受理中状态；后端执行检索与生成时为处理中状态；正常路径进入已回答状态（文字/语音输出），超时（达 5 秒上限）则进入超时状态并以降级提示收尾；最终所有记录进入已记录状态持久保存，作为感受度分析与数据大屏的数据源。状态转换如图 3-8 所示。"));
children.push(...fig("st0_zhuangtai.png", "图 3-8  问答记录状态转换图"));

// ===== 4 数据项字典 =====
children.push(h1("4  数据项字典"));
children.push(h2("4.1  数据项定义规范"));
children.push(p("本章按数据项七要素（字段名、业务含义、数据类型、是否必填、取值范围/默认值、枚举/字典、关联关系）对系统全部核心数据项进行定义。该字典与第 3.5 节类图一一对应，将直接用于后续数据库建表（DDL）、接口字段定义与入参校验规则的生成，实现从需求到设计的一致传递。"));
children.push(p("通用约定：金额与评分类字段统一使用 DECIMAL/DOUBLE，禁止使用 FLOAT；状态类字段全部枚举化并列全合法值；所有业务表统一附带 created_at 审计字段（类图中省略标注）；匿名游客不建用户记录，其交互通过前端生成并持久化于浏览器本地的 session_id（UUID）关联，注册登录后同一 session 的历史记录可关联至用户账号。"));

const entities = [
  ["4.2  User（用户）", "表 4-1  User（用户）数据项表", [
    ["id", "用户唯一标识", "BIGINT", "是", "自增，> 0", "—", "主键"],
    ["username", "登录用户名", "VARCHAR(50)", "是", "3~50 字符，全局唯一", "—", "唯一索引"],
    ["password", "登录密码（BCrypt 哈希）", "VARCHAR(100)", "是", "加密存储，禁明文", "—", "—"],
    ["role", "用户角色", "VARCHAR(20)", "是", "默认 VISITOR", "VISITOR（游客）/ ADMIN（管理员）", "管理员账号由初始化脚本预置"],
    ["created_at", "注册时间", "DATETIME", "是", "默认当前时间", "—", "—"],
  ]],
  ["4.3  Visitor（游客档案）", "表 4-2  Visitor（游客档案）数据项表", [
    ["id", "游客档案标识", "BIGINT", "是", "自增，> 0", "—", "主键；关联 user.id（一对一）"],
    ["nickname", "游客昵称", "VARCHAR(50)", "是", "1~50 字符", "—", "—"],
    ["interest_tags", "兴趣标签集合", "JSON", "否", "匿名时存前端本地，注册后落库", "HISTORY（历史）/ CULTURE（文化）/ NATURE（自然风光）/ FOOD（美食）/ FAMILY（亲子）", "推荐路线匹配依据"],
    ["preference", "偏好补充说明", "VARCHAR(200)", "否", "≤ 200 字符", "—", "—"],
    ["created_at", "档案创建时间", "DATETIME", "是", "默认当前时间", "—", "—"],
  ]],
  ["4.4  ScenicSpot（景点）", "表 4-3  ScenicSpot（景点）数据项表", [
    ["id", "景点唯一标识", "BIGINT", "是", "自增，> 0", "—", "主键"],
    ["name", "景点名称", "VARCHAR(100)", "是", "1~100 字符，唯一", "—", "知识文档/推荐路线/识景记录引用"],
    ["intro", "景点简介", "TEXT", "否", "—", "—", "—"],
    ["history", "景点历史资料", "TEXT", "否", "—", "—", "—"],
    ["culture", "景点文化资料", "TEXT", "否", "—", "—", "—"],
    ["image_url", "景点示例图地址", "VARCHAR(500)", "否", "合法 URL", "—", "—"],
    ["coordinates", "景点坐标", "VARCHAR(50)", "否", "经纬度文本", "—", "—"],
    ["created_at", "创建时间", "DATETIME", "是", "默认当前时间（脚本预置）", "—", "—"],
  ]],
  ["4.5  KnowledgeDoc（知识库文档）", "表 4-4  KnowledgeDoc（知识库文档）数据项表", [
    ["id", "文档唯一标识", "BIGINT", "是", "自增，> 0", "—", "主键；分片表 doc_id 外键来源"],
    ["title", "文档标题", "VARCHAR(200)", "是", "1~200 字符", "—", "—"],
    ["content", "文档原始内容", "TEXT", "是", "非空", "—", "分片（KnowledgeChunk）的来源"],
    ["type", "文档类型", "VARCHAR(20)", "是", "—", "SCRIPT（讲解词）/ MATERIAL（文史资料）/ FAQ（常见问题）", "—"],
    ["status", "处理状态", "TINYINT", "是", "默认 0", "0（待处理）/ 1（已入库）/ 2（失败）", "—"],
    ["created_at", "上传时间", "DATETIME", "是", "默认当前时间", "—", "—"],
  ]],
  ["4.6  KnowledgeChunk（知识分片）", "表 4-5  KnowledgeChunk（知识分片）数据项表", [
    ["id", "分片记录标识", "BIGINT", "是", "自增，> 0", "—", "主键"],
    ["doc_id", "所属文档标识", "BIGINT", "是", "—", "—", "关联 knowledge_doc.id（一对多）"],
    ["chunk_no", "分片序号", "INT", "是", "≥ 1，文档内递增", "—", "—"],
    ["content", "分片文本内容", "TEXT", "是", "非空", "—", "—"],
    ["vector_id", "Redis 向量库中的分片键", "VARCHAR(64)", "是", "入库成功后写入", "—", "关联 Redis 向量库"],
    ["status", "分片状态", "TINYINT", "是", "默认 0", "0（有效）/ 1（已清理）", "文档更新/删除时同步清理"],
    ["created_at", "入库时间", "DATETIME", "是", "默认当前时间", "—", "—"],
  ]],
  ["4.7  QARecord（问答记录）", "表 4-6  QARecord（问答记录）数据项表", [
    ["id", "问答记录标识", "BIGINT", "是", "自增，> 0", "—", "主键"],
    ["session_id", "会话标识", "VARCHAR(36)", "是", "UUID，前端生成", "—", "匿名交互关联；注册后可关联 user.id"],
    ["user_id", "提问用户标识", "BIGINT", "否", "匿名提问为 NULL", "—", "关联 user.id"],
    ["question", "游客问题文本（或图片描述）", "TEXT", "是", "非空", "—", "—"],
    ["answer", "系统回答文本", "TEXT", "是", "非空", "—", "—"],
    ["interaction_type", "交互输入类型", "VARCHAR(10)", "是", "—", "VOICE（语音）/ TEXT（文字）/ IMAGE（图片）", "—"],
    ["answer_type", "回答输出形式", "VARCHAR(20)", "是", "—", "TEXT（文字）/ VOICE（语音）/ TEXT_VOICE（文字+语音）", "—"],
    ["image_url", "拍照识景图片地址", "VARCHAR(500)", "否", "合法 URL，仅 IMAGE 型记录填写", "—", "—"],
    ["recognized_spot_id", "识景命中的景点", "BIGINT", "否", "未命中为 NULL", "—", "关联 scenic_spot.id"],
    ["satisfaction", "游客评价", "VARCHAR(10)", "否", "默认 NULL（未评价）", "GOOD（好评）/ BAD（差评）/ NULL（未评价）", "满意度趋势数据源"],
    ["latency_ms", "端到端时延（毫秒）", "INT", "是", "≥ 0，阈值 5000", "—", "性能统计依据"],
    ["sentiment_score", "情感得分", "DOUBLE", "否", "-1.0 ~ 1.0，NULL 表示未分析", "—", "感受度报告数据源"],
    ["created_at", "提问时间", "DATETIME", "是", "默认当前时间", "—", "—"],
  ]],
  ["4.8  ConversationLog（交互日志）", "表 4-7  ConversationLog（交互日志）数据项表", [
    ["id", "交互日志标识", "BIGINT", "是", "自增，> 0", "—", "主键"],
    ["session_id", "会话标识", "VARCHAR(36)", "是", "UUID，前端生成", "—", "与 QARecord.session_id 同源"],
    ["user_id", "交互用户标识", "BIGINT", "否", "匿名为 NULL", "—", "关联 user.id"],
    ["interaction_type", "交互类型", "VARCHAR(10)", "是", "—", "VOICE（语音）/ TEXT（文字）/ IMAGE（图片）", "—"],
    ["latency_ms", "交互时延（毫秒）", "INT", "是", "≥ 0", "—", "—"],
    ["success", "交互是否成功", "TINYINT", "是", "默认 1", "1（成功）/ 0（失败）", "—"],
    ["created_at", "交互时间", "DATETIME", "是", "默认当前时间", "—", "—"],
  ]],
  ["4.9  Route（推荐路线）", "表 4-8  Route（推荐路线）数据项表", [
    ["id", "路线唯一标识", "BIGINT", "是", "自增，> 0", "—", "主键"],
    ["name", "路线名称", "VARCHAR(100)", "是", "1~100 字符", "—", "—"],
    ["spot_ids", "路线包含的景点序列", "JSON", "是", "至少 2 个景点，保持游览顺序", "—", "元素关联 scenic_spot.id"],
    ["suitable_tags", "适用兴趣标签", "JSON", "是", "至少 1 个标签", "同 interest_tags 字典", "与游客标签匹配"],
    ["created_at", "创建时间", "DATETIME", "是", "默认当前时间（脚本预置）", "—", "—"],
  ]],
  ["4.10  DigitalHuman（数字人形象）", "表 4-9  DigitalHuman（数字人形象）数据项表", [
    ["id", "形象配置标识", "BIGINT", "是", "自增，> 0", "—", "主键"],
    ["appearance", "外观方案", "VARCHAR(50)", "是", "预设 2~3 套", "如 CLASSIC（经典）/ LIVELY（活泼）", "—"],
    ["outfit", "服装方案", "VARCHAR(50)", "是", "预设方案", "预设枚举", "—"],
    ["voice", "音色方案", "VARCHAR(50)", "是", "Edge-TTS 预设音色", "预设枚举", "—"],
    ["is_active", "是否当前启用", "TINYINT", "是", "默认 0", "1（启用）/ 0（停用）", "全表至多一条为 1"],
    ["updated_at", "最近更新时间", "DATETIME", "是", "默认当前时间", "—", "—"],
    ["created_at", "创建时间", "DATETIME", "是", "默认当前时间", "—", "—"],
  ]],
  ["4.11  Report（感受度报告）", "表 4-10  Report（感受度报告）数据项表", [
    ["id", "报告唯一标识", "BIGINT", "是", "自增，> 0", "—", "主键"],
    ["period", "统计周期类型", "VARCHAR(20)", "是", "—", "DAY（日）/ WEEK（周）/ MONTH（月）", "—"],
    ["period_start", "周期起始日期", "DATE", "是", "—", "—", "—"],
    ["period_end", "周期结束日期", "DATE", "是", "≥ period_start", "—", "—"],
    ["qa_count", "周期内问答总数", "INT", "是", "≥ 0", "—", "数据大屏直接引用"],
    ["satisfaction_rate", "周期内好评率", "DOUBLE", "是", "0.0 ~ 1.0（好评数/已评价数）", "—", "满意度趋势数据源"],
    ["avg_latency_ms", "周期内平均时延", "INT", "是", "≥ 0", "—", "性能趋势数据源"],
    ["focus_points", "关注点分析结论", "TEXT", "是", "由问答记录聚合生成", "—", "数据源 QARecord"],
    ["sentiment_trend", "情感趋势结论", "TEXT", "是", "由情感得分聚合生成", "—", "数据源 QARecord.sentiment_score"],
    ["service_suggestions", "服务改进建议", "TEXT", "是", "基于关注点与趋势生成", "—", "—"],
    ["generated_at", "报告生成时间", "DATETIME", "是", "默认当前时间", "—", "—"],
  ]],
];

for (const [title, cap, rows] of entities) {
  children.push(h3(title));
  children.push(tableCaption(cap));
  children.push(diTable(rows));
}

children.push(h2("4.12  数据项到设计的衔接"));
children.push(p("数据项字典定稿后，数据库建表语句（含景点与路线的初始化脚本）、后端接口字段定义（请求/响应结构）、前后端入参校验规则与测试数据均可直接由本字典生成，保证需求、接口、实现三者的字段命名与约束一致，避免前后端各自理解造成的返工。"));

// ===== 5 业务规则 =====
children.push(h1("5  业务规则"));
const rules = [
  ["BR-01", "管理后台所有功能（知识库管理、形象管理、感受度报告、数据大屏）须先通过登录认证（JWT，有效期 2 小时），未认证或令牌失效的请求一律返回 401 并引导重新登录；管理员账号由数据库初始化脚本预置。"],
  ["BR-02", "游客可免登录使用问答、拍照识景、语音讲解与基础推荐；匿名期间由前端生成并本地持久化的会话标识（session_id）关联交互记录，兴趣标签存于浏览器本地；注册/登录后兴趣标签写入游客档案持久化，个性化推荐优先使用持久化标签。"],
  ["BR-03", "事实性问答的回答必须基于本地知识库检索结果生成；检索无依据时须明确告知游客暂无相关资料，不得凭模型自身知识编造景区事实。"],
  ["BR-04", "知识文档入库须完整经过解析、清洗、分片、BGE 向量化管线；向量分片写入 Redis 向量库并登记分片明细（KnowledgeChunk），双写成功后文档状态方可置为已入库并可被检索。"],
  ["BR-05", "单次语音问答端到端时延（游客结束说话至数字人开口播报首个字）超过 5 秒视为超时，系统自动中断等待并给出降级提示与重试入口，禁止无限等待。"],
  ["BR-06", "全部问答记录与交互日志全量留存，作为游客感受度报告与数据大屏统计的唯一数据来源。"],
  ["BR-07", "数字人外观、服装、音色仅允许在预设方案间切换配置，不支持自由上传或自定义形象资产；同一时刻仅一套配置处于启用（is_active）状态。"],
  ["BR-08", "拍照识景仅在多模态模型识别置信度不低于 0.6 且与知识库景点匹配成功时输出讲解；无法匹配时引导游客改用文字或语音提问，不输出猜测性内容。"],
  ["BR-09", "知识文档更新或删除时，须先按文档标识清理 Redis 向量库中对应全部分片，再更新/删除 MySQL 元数据（分片明细同步标记已清理），保证向量库与关系库一致。"],
  ["BR-10", "数据大屏的游客满意度定义为统计周期内好评数 / 已评价问答数（游客可对回答点赞或点踩，默认未评价），与情感得分（sentiment_score）为两个独立指标。"],
];
children.push(tableCaption("表 5-1  业务规则清单"));
children.push(twoColTable(["编号", "规则描述"], rules, [1200, 7826]));

// ===== 6 非功能需求 =====
children.push(h1("6  非功能需求"));
children.push(h2("6.1  性能需求"));
children.push(bullet("延迟定义与指标：端到端延迟定义为「游客结束说话（或提交文字）起，至数字人开口播报首个字（TTS 首包）止」，语音问答 < 5 秒（设计目标 2~3 秒），文字问答首字返回 < 2 秒；"));
children.push(bullet("并发与规模：支持 50 并发游客会话；知识库文档规模 ≤ 500 篇；单次语音输入 ≤ 60 秒；单张识景图片 ≤ 10MB；"));
children.push(bullet("管理后台常规查询页面响应 < 1 秒；数据大屏分钟级自动刷新（演示环境 30 秒间隔）。"));
children.push(h2("6.2  准确性需求"));
children.push(p("事实性问答准确率 ≥ 90%。验收方式：从知识库语料构造不少于 100 题的标准测试集（客观题与简答题混合，覆盖历史、文化、景点特色、FAQ 四类），由两名评审独立判定回答是否事实正确，两人一致判定正确记为通过，通过率 ≥ 90% 视为达标。"));
children.push(h2("6.3  自然度需求"));
children.push(p("数字人回答须做到口型与播报语音同步、TTS 音色自然流畅、表情与回答语义配合。验收方式：由 3 名评审观看演示视频，按口型同步、语音自然度、表情配合三个维度分别按 1~5 分评分，每个维度平均分 ≥ 3.5 视为通过。"));
children.push(h2("6.4  可靠性需求"));
children.push(p("演示与运行期间系统连续运行 2 小时不崩溃、不白屏。外部 AI 服务（DeepSeek、Edge-TTS）异常时系统具备降级能力（友好提示、高频问答缓存兜底），不将底层错误直接暴露给游客。"));
children.push(h2("6.5  安全性与隐私"));
children.push(bullet("管理端接口全部经过 JWT 认证与角色校验，全站 HTTPS；"));
children.push(bullet("用户密码 BCrypt 哈希存储，禁止明文；登录连续失败 5 次锁定 10 分钟；"));
children.push(bullet("知识文档上传按类型白名单校验（pdf/txt/md/docx）；所有接口入参按数据项字典进行类型、长度、枚举校验；"));
children.push(bullet("隐私声明：游客语音与图片仅用于当次识别与问答，不用于模型训练；调用外部 AI 服务时不传输游客身份信息；演示数据保留至课程评审结束。"));
children.push(h2("6.6  可部署性需求"));
children.push(p("系统以 Docker Compose 一键部署，覆盖 MySQL、Redis、后端服务与前端应用，交付物包含安装部署文档与镜像。外部 AI 服务依赖（DeepSeek、Edge-TTS、Web Speech API）通过环境变量统一配置与管理，服务不可用时按 6.4 节策略降级。"));
children.push(h2("6.7  兼容性需求"));
children.push(p("游客端与管理端适配 Chrome / Edge 最新两个大版本（桌面端为主）；移动端浏览器基础可用，语音与相机能力依赖浏览器支持，不支持时自动降级为文字输入。"));

// ---------- document ----------
const doc = new Document({
  creator: "第7组",
  title: "第7组-景区导览服务AI数字人系统需求分析文档",
  styles: {
    default: {
      document: { run: { font: FONT, size: 24 } },
    },
    paragraphStyles: [
      { id: "Heading1", name: "Heading 1", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { size: 32, bold: true, font: FONT },
        paragraph: { spacing: { before: 240, after: 240 }, outlineLevel: 0, keepNext: false, keepLines: false } },
      { id: "Heading2", name: "Heading 2", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { size: 28, bold: true, font: FONT },
        paragraph: { spacing: { before: 200, after: 160 }, outlineLevel: 1, keepNext: false, keepLines: false } },
      { id: "Heading3", name: "Heading 3", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { size: 24, bold: true, font: FONT },
        paragraph: { spacing: { before: 160, after: 120 }, outlineLevel: 2, keepNext: false, keepLines: false } },
    ],
  },
  numbering: {
    config: [
      { reference: "bullets",
        levels: [{ level: 0, format: LevelFormat.BULLET, text: "•", alignment: AlignmentType.LEFT,
          style: { paragraph: { indent: { left: 720, hanging: 360 } } } }] },
    ],
  },
  sections: [{
    properties: {
      page: {
        size: { width: 11906, height: 16838 },
        margin: { top: 1440, right: 1440, bottom: 1440, left: 1440 },
      },
      titlePage: true,
    },
    headers: {
      first: new Header({ children: [] }),
      default: new Header({
        children: [new Paragraph({
          alignment: AlignmentType.CENTER,
          children: [new TextRun({ text: "景区导览服务 AI 数字人系统需求分析文档", size: 18, color: "888888" })],
        })],
      }),
    },
    footers: {
      first: new Footer({ children: [] }),
      default: new Footer({
        children: [new Paragraph({
          alignment: AlignmentType.CENTER,
          children: [new TextRun({ children: ["第 ", PageNumber.CURRENT, " 页"], size: 18, color: "888888" })],
        })],
      }),
    },
    children,
  }],
});

Packer.toBuffer(doc).then(buf => {
  fs.writeFileSync(OUT, buf);
  console.log("OK ->", OUT, buf.length, "bytes");
});
