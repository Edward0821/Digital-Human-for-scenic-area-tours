# Digital Human for Scenic Area Tours · 景区导览服务 AI 数字人

> 软件工程课程设计（2024级）· 第 7 组 · 团队 6 人 · 组长：Edward0821

## 项目简介

面向景区的 AI 数字人导览服务：游客可通过**文字 / 语音问答**获取景点信息，数字人形象实时交互，支持**拍照识景**；管理端提供**知识库管理**、形象配置、游客感受度报告与数据大屏。

## 技术栈

- 后端：Spring Boot 3.5 + MyBatis-Plus + MySQL 8 + Redis（Redis Stack 向量检索）+ Spring AI（RAG）
- 前端：Vue3 + Vue Router + axios + Web Speech API（浏览器端 ASR）
- AI：DeepSeek API（LLM + 拍照识景 VL）、BGE 向量模型（知识检索）、Edge-TTS（语音合成）
- 部署：Docker Compose

## 目录结构

```
├─ docs/        # 规范文档、需求分析文档、接口设计说明书（含 docx 生成脚本）
├─ backend/     # Spring Boot 3.5 后端工程（含 README）
├─ frontend/    # Vue3 前端工程（含 README）
├─ deploy/      # Docker Compose 部署（已落地：Nginx:80 单入口 + 静态缓存 + /api 反代）
├─ mysql/       # 数据库三段式初始化脚本（01 建库建账号 / 02 建 14 表 / 03 初始数据）
└─ README.md
```

## 快速启动

```bash
# 0. 初始化数据库（本机 MySQL；Docker 部署则由容器自动执行同批脚本）
mysql -u root -p < mysql/init/01_create_db.sql
mysql -u root -p scenic_tour < mysql/init/02_tables.sql
mysql -u root -p scenic_tour < mysql/init/03_init_data.sql

# 1. 后端（JDK 21 + Maven）；数据库密码放 src/main/resources/application-local.yml
cd backend && mvn spring-boot:run      # http://localhost:8080/api/health

# 2. 前端（Node.js 20+）
cd frontend && npm install --registry=https://registry.npmmirror.com
npm run dev                             # http://localhost:5173（已代理 /api → 8080）
```

- 游客问答：`/`，管理后台：`/admin`
- 文件存储演示（大文件分片/断点续传/小文件批量/下载进度条）：`/files`

详细说明见 [backend/README.md](backend/README.md) 与 [frontend/README.md](frontend/README.md)。

## 分支与协作

- `main`：仅通过 PR 合入，不直推
- `backend` / `frontend`：前后端功能分支
- `doc/姓名`：文档分支，每人维护自己的
- 规范见 [docs/项目代码规范与协作规范.md](docs/项目代码规范与协作规范.md)

## 进度

- [x] 定题 / 破题分析 / 架构 / 分工
- [x] 仓库联通 + 项目骨架
- [x] 代码规范成文：《项目代码规范与协作规范》v0.2
- [x] 需求分析文档（用例图 / 时序图 / 类图 / 状态转换图 / 功能架构图 + 数据项字典，8图17表）
- [x] 前后端脚手架（可编译、可启动、联调探活打通）
- [x] 数据库：14 表三段式 SQL（共享表空间 ts_scenic / 最小权限业务账号 / INSERT IGNORE 幂等）
- [x] API 契约冻结：36 接口 / 9 模块（接口清单 v0.2 + OpenAPI 3.0 JSON + Apifox 已导入联调）
- [x] 接口设计说明书 docx（脚本从 OpenAPI JSON 自动生成，定义与文档同源）
- [x] 动静分离部署：deploy/ 四容器验收全绿（80 单入口 / 静态 7 天缓存 / /api 反代 / SPA 兜底）
- [x] 文件存储模块：大文件分片上传（单片 md5 校验坏块重传、缺片核对、FileChannel 重组、整体终校）、断点续传（上传分片续传 + 下载 Range/206）、小文件批量直传、下载进度条（`/files` 演示页）
- [ ] RAG 检索问答 + 文字问答
- [ ] 语音交互 + 数字人形象 + 管理端
- [ ] 全部交付物文档终稿 + 演示视频 + 答辩
