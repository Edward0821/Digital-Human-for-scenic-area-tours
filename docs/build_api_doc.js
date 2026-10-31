// 第 7 组 · 接口设计说明书生成脚本（按老师模板结构：汇总表 + 编码表 + 逐接口四表 + 数据模型附录）
// 数据源（工具定义，非手写）：
//   1) scenic-api-apifox.json —— OpenAPI 3.0（Apifox 导入用同源文件，36 操作 / 16 模型）
//   2) 接口清单-第7组.md —— v0.2 冻结底稿（全局约定 / 汇总表 / 安全策略 / 错误码表）
// 运行：node build_api_doc.js ["输出名.docx"]
const fs = require("fs");
const path = require("path");
const {
  Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell,
  AlignmentType, BorderStyle, WidthType, ShadingType, HeadingLevel,
  TableOfContents, PageBreak, Footer, PageNumber, NumberFormat,
} = require("docx");

const ROOT = path.join(__dirname, "..", ".."); // 软件工程工作区根目录
const MD_PATH = path.join(ROOT, "接口清单-第7组.md");
const JSON_PATH = path.join(ROOT, "scenic-api-apifox.json");
const FONT = "Microsoft YaHei";
const MONO = { ascii: "Consolas", hAnsi: "Consolas", eastAsia: FONT };

// ---------- 基础构件（与 build_db_doc.js 同款） ----------
const p = (text, opts = {}) =>
  new Paragraph({
    spacing: { after: 120 },
    children: [new TextRun({ text, bold: opts.bold, size: opts.size || 22 })],
  });
const h1 = (text) => new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun(text)] });
const h2 = (text) => new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun(text)] });
const h3 = (text) => new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun(text)] });

const cell = (text, { bold = false, fill = null, width, size = 20 } = {}) =>
  new TableCell({
    width: width ? { size: width, type: WidthType.DXA } : undefined,
    shading: fill ? { fill, type: ShadingType.CLEAR } : undefined,
    margins: { top: 60, bottom: 60, left: 100, right: 100 },
    children: [new Paragraph({ children: [new TextRun({ text, bold, size })] })],
  });

const border = { style: BorderStyle.SINGLE, size: 1, color: "999999" };
const borders = { top: border, bottom: border, left: border, right: border };

const genTable = (header, rows, widths, size = 20) =>
  new Table({
    width: { size: widths.reduce((a, b) => a + b, 0), type: WidthType.DXA },
    columnWidths: widths,
    rows: [
      new TableRow({
        cantSplit: true,
        tableHeader: true,
        children: header.map((t, i) => cell(t, { bold: true, fill: "D9E2F3", width: widths[i], size })),
      }),
      ...rows.map(
        (r) =>
          new TableRow({
            cantSplit: true,
            children: r.map((t, i) => cell(String(t), { width: widths[i], size })),
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

// 表格列宽：按各列最长内容等比分配，总量 9360 DXA
function colWidths(header, rows, total = 9360) {
  const n = header.length;
  const maxLen = header.map((h, i) =>
    Math.max(String(h).length * 2, ...rows.map((r) => String(r[i] || "").length))
  );
  const weights = maxLen.map((l) => Math.max(l + 2, 6));
  const sum = weights.reduce((a, b) => a + b, 0);
  const ws = weights.map((w) => Math.max(Math.round((w / sum) * total), Math.round(total / (n * 3.2))));
  const over = ws.reduce((a, b) => a + b, 0) - total;
  ws[ws.indexOf(Math.max(...ws))] -= over;
  return ws;
}

// ---------- Markdown 子集渲染（清单冻结版 → docx） ----------
function inlineRuns(text, base = {}) {
  const runs = [];
  const re = /(\*\*[^*]+\*\*|`[^`]+`)/g;
  let last = 0, m;
  while ((m = re.exec(text))) {
    if (m.index > last) runs.push(new TextRun({ text: text.slice(last, m.index), size: 22, ...base }));
    const tok = m[0];
    if (tok.startsWith("**")) runs.push(new TextRun({ text: tok.slice(2, -2), bold: true, size: 22, ...base }));
    else runs.push(new TextRun({ text: tok.slice(1, -1), font: MONO, size: 20, ...base }));
    last = m.index + tok.length;
  }
  if (last < text.length) runs.push(new TextRun({ text: text.slice(last), size: 22, ...base }));
  return runs;
}

const stripMd = (s) => String(s).replace(/\*\*/g, "").replace(/`/g, "").trim();

function mdToChildren(lines, { headingLevel = 2 } = {}) {
  const out = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (/^---\s*$/.test(line)) { i++; continue; }
    if (/^### /.test(line)) {
      const htext = line.slice(4).replace(/\*\*/g, "").replace(/`/g, "").trim();
      out.push(headingLevel === 2 ? h2(htext) : h3(htext));
      i++; continue;
    }
    if (/^\| /.test(line)) {
      const tbl = [];
      while (i < lines.length && /^\|/.test(lines[i])) {
        const raw = lines[i].trim();
        if (!/^\|[\s:|-]+\|$/.test(raw)) { // 跳过 |---|---| 分隔行
          const cells = raw.split("|").slice(1, -1).map(stripMd);
          tbl.push(cells);
        }
        i++;
      }
      if (tbl.length) {
        const header = tbl[0];
        const rows = tbl.slice(1);
        out.push(genTable(header, rows, colWidths(header, rows), header.length >= 6 ? 18 : 20));
        out.push(new Paragraph({ spacing: { after: 80 }, children: [] }));
      }
      continue;
    }
    if (/^> /.test(line)) {
      const text = line.slice(2).trim();
      out.push(new Paragraph({
        spacing: { after: 120 },
        indent: { left: 300 },
        children: inlineRuns(text, { color: "595959", size: 20 }),
      }));
      i++; continue;
    }
    if (/^\d+\. /.test(line) || /^- /.test(line)) {
      out.push(new Paragraph({
        spacing: { after: 100 },
        indent: { left: 300 },
        children: inlineRuns(line.replace(/^(-|\d+\.) /, "").trim()),
      }));
      i++; continue;
    }
    if (line.trim()) out.push(new Paragraph({ spacing: { after: 120 }, children: inlineRuns(line.trim()) }));
    i++;
  }
  return out;
}

function mdSection(md, startMark, endMark) {
  const lines = md.split("\n");
  const s = lines.findIndex((l) => l.startsWith(startMark));
  if (s < 0) throw new Error("未找到章节：" + startMark);
  let e = lines.length;
  if (endMark) {
    e = lines.findIndex((l, i) => i > s && l.startsWith(endMark));
    if (e < 0) throw new Error("未找到章节边界：" + endMark);
  }
  return lines.slice(s + 1, e);
}

// ---------- 交付版清洗：去课程过程口吻（内部底稿 → 交付文档） ----------
const REPLACE_MAP = [
  ["草图原键", "数据库设计原键"],
  ["与草图口径一致", "与数据库设计口径一致"],
  ["草图刻意不建", "数据库设计刻意不建"],
  ["草图", "数据库设计说明书"],
  ["课件", "课程规范"],
  ["评审新增", "设计迭代新增"],
  ["评审修正：", ""],
  ["——答辩口径：", "；"],
  ["答辩口径：", ""],
  ['验证"删干净"答辩点', "验证分片与索引均已删除"],
];
const DROP_RE = /答辩|拍板|v0\.1|外部评审|老师点名|第八周|硬要求|自查/;
const REGEX_RULES = [
  [/（对齐课程规范 ?slide[^）]*）/g, ""],
  [/（课程规范 ?slide[^）]*）/g, ""],
  [/，见第五节/g, ""],
  // 全局约定章在交付版为第 2 章，小节号 1.x 随章重编为 2.x
  [/^### 1\.(\d) /, "### 2.$1 "],
];
const applyReplace = (s) => {
  let out = REPLACE_MAP.reduce((acc, [from, to]) => acc.split(from).join(to), String(s));
  return REGEX_RULES.reduce((acc, [re, to]) => acc.replace(re, to), out);
};
function sanitizeLines(lines) {
  return lines.filter((l) => !DROP_RE.test(l)).map(applyReplace);
}
// JSON 派生文本（op.description、参数/错误描述、示例）统一走 T()
const T = (s) => applyReplace(String(s));

// 公共字段说明兜底：JSON 未写 description 的常见字段由命名约定补齐（描述列不留空）
const COMMON_DESC = {
  id: "资源 ID（bigint 序列化为 string）", user_id: "用户 ID（bigint 序列化为 string）",
  session_id: "匿名会话 ID（前端 UUID）", record_id: "问答记录 ID", file_id: "文件 ID",
  doc_id: "知识文档 ID", dh_id: "数字人形象 ID", spot_id: "景点 ID", route_id: "路线 ID",
  chunk_id: "分片 ID", report_id: "报告 ID", token: "凭证", access_token: "JWT 访问令牌",
  token_type: "令牌类型（固定 Bearer）", expires_in: "令牌有效期（秒）",
  name: "名称", nickname: "昵称", title: "标题", type: "类型", status: "状态",
  level: "层级", sort: "排序序号（升序）", tags: "标签列表", image_url: "图片资源地址",
  page: "页码（从 1 起）", size: "每页条数（默认 20）", sort_field: "排序字段",
  question: "用户问题", answer: "回答文本", content: "内容文本", created_at: "创建时间（UTC ISO8601）",
  updated_at: "更新时间（UTC ISO8601）", total: "总条数", has_more: "是否还有下一页",
  list: "当前页数据列表", code: "业务响应码（0 成功）", message: "提示信息",
  data: "业务数据", trace_id: "全链路追踪 ID（5xxxx 必填）",
  is_active: "是否发布启用", is_uploaded: "是否已上传完成（秒传标记）",
  chunk_size: "分片大小（字节）", chunk_total: "分片总数", uploaded_chunks: "已收到分片号列表",
  md5: "文件内容 MD5（秒传/完整性校验依据）", file_size: "文件大小（字节）",
  storage_path: "服务器存储相对路径", file_type: "文件类型（白名单后缀）",
  file_name: "文件名", vector_id: "向量库主键", period: "统计周期粒度",
  period_start: "周期起始日（后端按粒度推算）", period_end: "周期结束日（后端按粒度推算）",
  preference: "个性化偏好描述", interest_tags: "兴趣标签列表",
};

// ---------- OpenAPI 解析 ----------
const api = JSON.parse(fs.readFileSync(JSON_PATH, "utf-8"));
const schemas = (api.components && api.components.schemas) || {};
const compResponses = (api.components && api.components.responses) || {};

function deref(schema) {
  if (schema && schema.$ref) {
    const name = schema.$ref.split("/").pop();
    return { name, ...(schemas[name] || {}) };
  }
  return schema || {};
}

// 由 schema 生成示例 JSON（全部使用定义中的真实 example，不造数）
function buildExample(schema, depth = 0) {
  if (!schema || depth > 6) return null;
  if (schema.$ref) {
    const name = schema.$ref.split("/").pop();
    const s = schemas[name];
    if (!s) return null;
    if (s.example !== undefined) return s.example;
    return buildExample({ ...s, $ref: undefined }, depth + 1);
  }
  if (schema.example !== undefined) return schema.example;
  if (schema.allOf) return Object.assign({}, ...schema.allOf.map((s) => buildExample(s, depth + 1) || {}));
  const t = schema.type || (schema.properties ? "object" : undefined);
  if (t === "object") {
    const o = {};
    for (const [k, v] of Object.entries(schema.properties || {})) {
      const e = buildExample(v, depth + 1);
      o[k] = e === undefined ? null : e;
    }
    return o;
  }
  if (t === "array") {
    const it = buildExample(schema.items, depth + 1);
    return [it === undefined || it === null ? null : it];
  }
  return { string: "string", integer: 0, number: 0, boolean: false }[t] ?? null;
}

function typeLabel(s) {
  if (!s) return "—";
  if (s.$ref) return s.$ref.split("/").pop();
  let t = s.type || "object";
  if (s.format) t += `(${s.format})`;
  if (s.enum) t = `枚举：${s.enum.join(" / ")}`;
  if (s.nullable) t += "，可空";
  if (t === "array" && s.items) t = "array<" + (s.items.$ref ? s.items.$ref.split("/").pop() : s.items.type || "object") + ">";
  return t;
}

// 请求参数行：[位置, 参数名, 类型, 必填, 说明, 示例值]
function bodyParamRows(op) {
  const rows = [];
  (op.parameters || []).forEach((prm) => {
    const s = deref(prm.schema);
    rows.push([
      { path: "path", query: "query", header: "header" }[prm.in] || prm.in,
      prm.name,
      typeLabel(s),
      prm.required ? "是" : "否",
      T(prm.description || ""),
      s.example !== undefined ? T(JSON.stringify(s.example)) : "",
    ]);
  });
  const body = op.requestBody;
  if (body && body.content) {
    for (const [ctype, media] of Object.entries(body.content)) {
      const s = deref(media.schema);
      if (ctype === "application/octet-stream" || (!s.properties && !s.allOf && s.type !== "object")) {
        rows.push(["body", "—", ctype, body.required ? "是" : "否", "二进制请求体（分片文件流）", "—"]);
        continue;
      }
      const required = s.required || [];
      const props = s.properties || {};
      for (const [k, v0] of Object.entries(props)) {
        const v = deref(v0);
        const desc = v.description || v.title || COMMON_DESC[k] || "";
        rows.push([
          "body",
          k,
          typeLabel(v0),
          required.includes(k) ? "是" : "否",
          T(desc || "—"),
          v.example !== undefined ? T(JSON.stringify(v.example)) : "",
        ]);
      }
      if (!Object.keys(props).length) rows.push(["body", "—", ctype, body.required ? "是" : "否", "请求体见数据模型附录", "—"]);
    }
  }
  return rows;
}

// 成功响应示例（code block 文本）
function successExample(op) {
  const resp = op.responses || {};
  const okKey = Object.keys(resp).find((k) => /^2/.test(k) && !resp[k].$ref);
  if (!okKey) return null;
  const content = resp[okKey].content || {};
  if (content["text/event-stream"]) {
    const s = content["text/event-stream"].schema || {};
    return { status: okKey, text: s.example || "(SSE 事件流)" };
  }
  const media = content["application/json"];
  if (!media) return null;
  if (media.example !== undefined) return { status: okKey, text: JSON.stringify(media.example, null, 2) };
  const built = buildExample(media.schema);
  return built ? { status: okKey, text: JSON.stringify(built, null, 2) } : null;
}

// 错误返回行：[HTTP, 场景与错误码]
function errorRows(op) {
  const rows = [];
  for (const [status, r] of Object.entries(op.responses || {})) {
    if (!/^4|^5/.test(status)) continue;
    const name = r.$ref ? r.$ref.split("/").pop() : null;
    const cr = name ? compResponses[name] : r;
    rows.push([status, cr ? T(cr.description) : ""]);
  }
  return rows;
}

// ---------- 清单解析（鉴权 / 成功码 映射） ----------
const md = fs.readFileSync(MD_PATH, "utf-8");
const summaryLines = mdSection(md, "## 二、", "## 三、");
const summaryMap = {};
summaryLines.forEach((l) => {
  const m = l.match(/^\|\s*\*{0,2}([A-Z]+-\d+)\*{0,2}\s*\|/);
  if (m) {
    const cells = l.split("|").slice(1, -1).map(stripMd);
    summaryMap[m[1]] = { name: cells[1], method: cells[2], path: cells[3], auth: cells[4], okCode: cells[5], desc: cells[6] };
  }
});

// ---------- 遍历 36 个操作（按 JSON 插入顺序 = 模块顺序） ----------
const operations = [];
for (const [pPath, methods] of Object.entries(api.paths)) {
  for (const [method, op] of Object.entries(methods)) {
    if (!op.summary) continue;
    const code = (op.summary.match(/^([A-Z]+-\d+)/) || [])[1] || "";
    operations.push({ code, method: method.toUpperCase(), pPath, tag: (op.tags || [""])[0], op, md: summaryMap[code] || {} });
  }
}
const tagOrder = [...new Set(operations.map((o) => o.tag))];

const codeLine = (t) =>
  new Paragraph({
    spacing: { after: 0 },
    children: [new TextRun({ text: t.length ? t : " ", font: MONO, size: 15 })],
  });
const codeBlock = (text) => String(text).replace(/\r\n/g, "\n").split("\n").forEach((t) => children.push(codeLine(t)));

// ---------- 文档组装 ----------
// 前置节：封面 + 目录（不编页码）
const front = [];

// 封面
front.push(new Paragraph({
  alignment: AlignmentType.CENTER, spacing: { before: 2400, after: 240 },
  children: [new TextRun({ text: "接口设计说明书", bold: true, size: 56 })],
}));
front.push(new Paragraph({
  alignment: AlignmentType.CENTER, spacing: { after: 240 },
  children: [new TextRun({ text: "—— 景区导览服务 AI 数字人系统 ——", size: 28 })],
}));
front.push(new Paragraph({
  alignment: AlignmentType.CENTER,
  children: [new TextRun({ text: "第 7 组", size: 26 })],
}));
front.push(new Paragraph({
  alignment: AlignmentType.CENTER, spacing: { before: 120 },
  children: [new TextRun({ text: "版本 v1.1    2026 年 10 月", size: 22, color: "666666" })],
}));
front.push(new Paragraph({ children: [new PageBreak()] }));

// 目录
front.push(new Paragraph({
  alignment: AlignmentType.CENTER, spacing: { after: 240 },
  children: [new TextRun({ text: "目  录", bold: true, size: 32 })],
}));
front.push(new TableOfContents("目录", { hyperlink: true, headingStyleRange: "1-2" }));

// 正文节：页码从 1 重新起算
const children = [];

// 1 引言
children.push(h1("1  引言"));
children.push(h2("1.1  编写目的"));
children.push(p("本文档定义“景区导览服务 AI 数字人系统”的 RESTful 接口设计，覆盖全局约定、接口汇总、统一返回编码、36 个接口的请求/响应明细与数据模型，作为后端实现、前端联调与接口测试（Apifox）的统一契约依据，保证接口定义、文档与实现三者一致。"));
children.push(p("预期读者：第 7 组全体开发成员、课程指导教师与评阅人。"));
children.push(h2("1.2  设计依据与生成方式"));
children.push(p("设计依据：数据库 14 张表（见《数据库设计说明书》）× 需求文档用例 × 课程接口规范（URL 命名、状态码口径、响应体字段、分页、安全策略）。推导方法：每张表找读写场景，支撑性表（sys_role、sys_user_role、conversation_log）内联在其他接口中，不单独开接口，实现“表全覆盖、接口不冗余”。"));
children.push(p("生成方式：本文档由生成脚本从 OpenAPI 3.0 定义文件（scenic-api-apifox.json，已导入 Apifox）与接口清单底稿（v0.2 冻结版）自动生成，接口明细、示例值与数据模型均取自工具定义文件，不做人工誊抄，保证文档与接口定义同源。"));

// 2 全局约定
children.push(h1("2  全局约定（所有接口共同遵守）"));
children.push(...mdToChildren(sanitizeLines(mdSection(md, "## 一、", "## 二、"))));

// 3 接口汇总表
children.push(h1("3  接口汇总表（9 模块 · 36 个）"));
children.push(...mdToChildren(sanitizeLines(summaryLines)));

// 4 统一返回编码表
children.push(h1("4  统一返回编码表"));
children.push(...mdToChildren(sanitizeLines(mdSection(md, "## 五、", "## 六、"))));

// 5 接口明细
children.push(h1("5  接口明细"));
children.push(p("每接口给出基本信息、请求参数、返回示例与错误返回四要素；示例值取自接口定义文件。各接口响应体的字段结构、类型与取值约束见附录数据模型（A.1~A.16），两者结合即构成完整的响应参数说明。按模块 01~09 组织，共 36 个接口。"));
let opSeq = 0;
let tableNo = 0; // 表号全文档连续分配（不再按每接口固定 4 张预留，消除缺号）
tagOrder.forEach((tag, ti) => {
  children.push(h2(`5.${ti + 1}  ${tag}`));
  let si = 0;
  operations.filter((o) => o.tag === tag).forEach((o) => {
    si++;
    opSeq++;
    children.push(h3(`5.${ti + 1}.${si}  ${o.op.summary}`));
    // 基本信息（"说明"取汇总速记与详细描述中更详尽者，避免同义反复）
    const cand = [o.md.desc, o.op.description].filter(Boolean).map((s) => T(s));
    const detail = cand.sort((a, b) => b.length - a.length)[0] || "—";
    const info = [
      ["接口编号", o.code],
      ["方法与路径", `${o.method} ${o.pPath}`],
      ["模块", o.tag],
      ["鉴权", T(o.md.auth || "—")],
      ["成功状态码", o.md.okCode || "—"],
      ["说明", detail],
    ];
    children.push(tableCaption(`表 5-${++tableNo}  ${o.code} 基本信息`));
    children.push(genTable(["项", "内容"], info, [1700, 7660]));
    // 请求参数
    const rows = bodyParamRows(o.op);
    if (rows.length) {
      children.push(tableCaption(`表 5-${++tableNo}  ${o.code} 请求参数`));
      children.push(genTable(["位置", "参数名", "类型", "必填", "说明", "示例值"], rows, colWidths(["位置", "参数名", "类型", "必填", "说明", "示例值"], rows), 18));
    } else {
      children.push(p("无请求参数。"));
    }
    // 返回示例
    const se = successExample(o.op);
    if (se) {
      children.push(tableCaption(`表 5-${++tableNo}  ${o.code} 返回示例（HTTP ${se.status}）`));
      codeBlock(T(se.text));
      children.push(new Paragraph({ spacing: { after: 80 }, children: [] }));
    }
    // 错误返回
    const errs = errorRows(o.op);
    if (errs.length) {
      children.push(tableCaption(`表 5-${++tableNo}  ${o.code} 错误返回`));
      children.push(genTable(["HTTP", "场景与错误码"], errs, colWidths(["HTTP", "场景与错误码"], errs)));
    }
  });
});

// 6 样板接口安全设计
children.push(h1("6  样板接口安全设计（QA-1 智能问答）"));
children.push(p("样板接口 POST /api/v1/qa/ask（智能问答）承载了本系统最完整的安全设计：覆盖认证、参数校验、限流、幂等、越权防护、输入清洗、大 payload 限制、超时降级、可观测、审计留痕与成本控制十一个方面，全部策略在服务端强制落地，如下表所示。"));
children.push(...mdToChildren(sanitizeLines(mdSection(md, "## 三、", "## 四、"))));

// 7 设计完备性对照
children.push(h1("7  设计完备性对照（数据表与 Redis 键归属）"));
children.push(p("本章给出 14 张数据表与 Redis 键到接口的覆盖对照，验证接口设计的完备性：所有存储资源均有接口承载，所有运行时键均有读写归属。"));
children.push(...mdToChildren(sanitizeLines(mdSection(md, "## 四、", "## 五、"))));
children.push(p("对照结果表明：14 张数据表全部被接口覆盖，10 个 Redis 键全部有读写归属，36 个接口无孤儿资源。支撑性表（sys_role、sys_user_role、conversation_log）内联于业务接口中，不单独提供 CRUD——接口设计服务于业务用例，而非对每张表做简单映射。"));

// 附录 A 数据模型
children.push(h1("附录  数据模型（16 个 Schema）"));
let schemaSeq = 0;
for (const [name, s] of Object.entries(schemas)) {
  schemaSeq++;
  children.push(h3(`A.${schemaSeq}  ${name}`));
  const props = s.properties || {};
  const rows = Object.entries(props).map(([k, v0]) => {
    const v = deref(v0);
    let desc = T(v.description || COMMON_DESC[k] || "");
    // 枚举取值只追加一次（v0 与 deref 后的 v 在 enum 场景是同一对象，避免重复拼接）
    if (v.enum && !desc.includes("枚举：") && !(v0.items && v0.items.enum)) desc = (desc ? desc + "；" : "") + "枚举：" + v.enum.join(" / ");
    if (v0.items && v0.items.enum) desc = (desc ? desc + "；" : "") + "元素枚举：" + v0.items.enum.join(" / ");
    return [k, typeLabel(v0), desc || "—", v.example !== undefined ? JSON.stringify(v.example) : (v0.example !== undefined ? JSON.stringify(v0.example) : "—")];
  });
  if ((s.required || []).length) rows.unshift(["必填字段", "—", s.required.join("、"), "—"]);
  children.push(genTable(["字段", "类型", "说明", "示例值"], rows, colWidths(["字段", "类型", "说明", "示例值"], rows), 18));
}

// ---------- 生成 ----------
const doc = new Document({
  creator: "第 7 组",
  title: "第7组-景区导览服务AI数字人系统接口设计说明书",
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
  sections: [
    { properties: {}, children: front },
    {
      properties: { page: { pageNumbers: { start: 1, formatType: NumberFormat.DECIMAL } } },
      footers: {
        default: new Footer({
          children: [new Paragraph({
            alignment: AlignmentType.CENTER,
            children: [new TextRun({ children: [PageNumber.CURRENT], size: 18, color: "666666" })],
          })],
        }),
      },
      children,
    },
  ],
});

Packer.toBuffer(doc).then((buf) => {
  const out = process.argv[2] || path.join(__dirname, "第7组-景区导览服务AI数字人系统接口设计说明书.docx");
  fs.writeFileSync(out, buf);
  console.log("已生成：" + out);
  console.log(`接口数：${operations.length}（应为 36）；模块数：${tagOrder.length}（应为 9）；数据模型：${schemaSeq} 个；鉴权映射命中：${Object.keys(summaryMap).length} 条`);
});
