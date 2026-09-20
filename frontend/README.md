# digital-human-frontend · 前端工程

景区导览服务 AI 数字人系统（第 7 组）前端工程。当前为项目脚手架：Vite + Vue3 + Vue Router，含 axios 统一封装、ESLint + Prettier 规范、前后端联调代理与系统状态页。

## 环境要求

- Node.js 20.19+ 或 22+（本仓库开发环境：Node 22）
- npm 10+

## 快速启动

```bash
cd frontend
npm install          # 国内网络慢可用：npm install --registry=https://registry.npmmirror.com
npm run dev          # 打开 http://localhost:5173
```

## 常用脚本

| 命令 | 作用 |
|------|------|
| `npm run dev` | 本地开发服务器（5173 端口，/api 代理到 8080） |
| `npm run build` | 生产构建（输出 dist/） |
| `npm run preview` | 预览构建产物 |
| `npm run lint` | ESLint 检查 |
| `npm run format` | Prettier 格式化 src/ |

## 前后端联调

- `vite.config.js` 已配置代理：前端所有 `/api` 请求转发到 `http://localhost:8080`（后端 Spring Boot）
- 后端就绪后，首页"后端服务状态"卡片会显示 `/api/health` 的实时探活结果（app / MySQL / Redis）

## 目录结构

```
frontend
├── index.html
├── vite.config.js          # Vite 配置（含 /api 代理）
├── eslint.config.js        # ESLint flat config
├── .prettierrc.json        # Prettier 规范
└── src
    ├── main.js             # 应用入口
    ├── App.vue             # 布局（顶部导航）
    ├── assets/main.css     # 全局样式
    ├── router/index.js     # 路由（游客问答 / 管理后台）
    ├── api
    │   ├── request.js      # axios 封装（统一处理 Result{code,message,data}）
    │   └── health.js       # 健康检查接口
    └── views
        ├── HomeView.vue    # 游客问答页（占位 + 系统状态）
        └── AdminView.vue   # 管理后台（模块占位）
```

## 接口约定

- 统一前缀 `/api`，代理转发到后端 8080
- 后端统一响应 `Result{code, message, data}`：code=200 成功、400 参数错误、500 系统错误
- `request.js` 响应拦截器自动解包：成功返回 data，失败 reject Error(message)

## 代码规范

- 遵循《项目代码规范与协作规范》（仓库 docs/ 目录）
- 提交前执行 `npm run lint` 通过

## 分支说明

本工程在 `frontend` 分支开发，通过 PR 合入 `main`，不直接推送 `main`。
