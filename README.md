# Digital Human for Scenic Area Tours · 景区导览服务 AI 数字人

> 软件工程课程设计（2024级）· 团队 6 人 · 组长：Edward0821

## 项目简介

面向景区的超绝 AI 数字人导览服务：游客可通过**文字 / 语音问答**获取景点信息，数字人形象实时交互，支持**拍照识景**；管理端提供**知识库管理**、形象配置与数据大屏!

## 技术栈

- 后端：Spring Boot 3 + MyBatis + MySQL + Redis Stack + RabbitMQ + Spring AI（RAG）+ Spring Security/JWT
- 前端：Vue3 + Element Plus + ECharts + Web Speech API
- AI：DeepSeek API（LLM）、Qwen-VL（拍照识景）、BGE 向量模型（知识检索）、Edge-TTS
- 部署：Docker Compose

## 目录结构

```
├─ docs/        # 规范文档、接口契约、设计文档
├─ backend/     # Spring Boot 3 后端
├─ frontend/    # Vue3 前端
├─ deploy/      # Docker Compose 部署
├─ mysql/       # 数据库初始化脚本
└─ README.md
```

## 进度

- [x] 定题 / 破题分析 / 架构 / 分工 / 时间线
- [x] 仓库联通 + 项目骨架
- [ ] 代码规范成文
- [ ] 后端骨架 + RAG + 文字问答
- [ ] 语音交互 + 数字人形象 + 管理端
- [ ] Docker 部署 + 全部交付物文档