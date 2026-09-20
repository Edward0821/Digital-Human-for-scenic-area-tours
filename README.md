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
├─ docs/        # 规范文档、需求分析文档、建模图
├─ backend/     # Spring Boot 3.5 后端工程（含 README）
├─ frontend/    # Vue3 前端工程（含 README）
├─ deploy/      # Docker Compose 部署（后续提供）
├─ mysql/       # 数据库初始化脚本
└─ README.md
```

## 快速启动

```bash
# 后端（JDK 21 + Maven）
cd backend && mvn spring-boot:run      # http://localhost:8080/api/health

# 前端（Node.js 20+）
cd frontend && npm install --registry=https://registry.npmmirror.com
npm run dev                             # http://localhost:5173（已代理 /api → 8080）
```

详细说明见 [backend/README.md](backend/README.md) 与 [frontend/README.md](frontend/README.md)。

## 分支与协作

- `main`：仅通过 PR 合入，不直推
- `backend` / `frontend`：前后端功能分支
- `doc/姓名`：文档分支，每人维护自己的
- 规范见 [docs/项目代码规范与协作规范.md](docs/项目代码规范与协作规范.md)

## 进度

- [x] 定题 / 破题分析 / 架构 / 分工
- [x] 仓库联通 + 项目骨架
- [x] 代码规范成文：《项目代码规范与协作规范》
- [x] 需求分析文档（用例图 / 时序图 / 类图 / 状态转换图 / 功能架构图 + 数据项字典）
- [x] 前后端脚手架（可编译、可启动、联调探活打通）
- [ ] RAG 检索问答 + 文字问答
- [ ] 语音交互 + 数字人形象 + 管理端
- [ ] Docker 部署 + 全部交付物文档
