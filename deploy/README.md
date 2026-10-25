# 部署说明（Docker Compose 一键部署 · 动静分离架构）

> 本目录是期末交付物《系统安装部署文档（含 docker 镜像）》的工程雏形，由 Nginx 统一入口实现**动静分离**。

## 一、架构总览

```
浏览器
  │
  ▼ :80
Nginx（scenic-web 容器）
  ├─ 静：/            → Vue3 构建产物（容器内静态文件，7 天缓存）
  └─ 动：/api/**      → 反代 scenic-backend:8080（Spring Boot）
                          ├─ scenic-mysql（MySQL 8.0，数据卷持久化）
                          └─ scenic-redis（Redis 7）
```

要点：
- **只有一个入口端口（80）**：后端/数据库/缓存都不对外暴露，符合生产形态
- **静态动态分离**：js/css/图片由 Nginx 直接返回并长缓存；接口流量才进入后端
- 前后端镜像均为**多阶段构建**：构建层（Maven/Node）与运行层（JRE/Nginx）分离，镜像更小、依赖不泄漏

## 二、首次部署（三条命令）

前置条件：Docker Desktop 已启动。

```powershell
cd deploy
Copy-Item .env.example .env      # 已有 .env 可跳过；密码自行修改
docker compose up -d --build     # 首次构建约 5~10 分钟（拉镜像+下载依赖）
```

完成后浏览器访问 <http://localhost> 即为系统首页。

## 三、部署验证清单

| 验证项 | 操作 | 期望结果 |
|--------|------|----------|
| 静态页 | 打开 http://localhost | 数字人导览首页正常渲染 |
| SPA 路由 | 直接访问 http://localhost/admin | 不 404，正常进入管理页 |
| 动态接口 | 首页"后端服务状态"卡片 / http://localhost/api/health | 返回 JSON，MySQL/Redis 状态 UP |
| 容器状态 | `docker compose ps` | 四个容器均 healthy/running |

## 四、动静分离演示脚本（给老师看的四步）

1. **看静态**：首页 F12 → Network → 刷新。js/css 请求的响应头有 `Server: nginx`、`Cache-Control: public, max-age=604800`，Size 列显示"(memory cache)"或耗时 0ms —— 静态资源由 Nginx 直接返回，未经过后端
2. **看动态**：Network 里 `/api/health` 请求有完整网络耗时（经反代进入 Spring Boot），响应头无缓存设置 —— 动态与静态走两条路
3. **断后端**：`docker stop scenic-backend` 后刷新首页 —— 页面照样渲染（静态不依赖后端），但"服务状态"卡片显示不可达 —— 两路彻底分离的实证
4. **恢复**：`docker compose start scenic-backend`，几秒后刷新，状态卡片恢复 UP（顺带演示了容器自愈）

## 五、端口与冲突说明

| 端口 | 占用方 | 说明 |
|------|--------|------|
| 80 | scenic-web | 与《集群实验》的 lab-nginx 冲突，两者不能同时启动；需同时演示时先 `docker compose down` 另一方 |
| 8080 | scenic-backend（内部） | 不映射到宿主机，无冲突 |
| 3306/6379 | scenic-mysql / scenic-redis（内部） | 不映射；宿主机自带 MySQL/Redis 互不影响 |

## 六、日常运维命令

```powershell
docker compose logs -f scenic-backend     # 看后端日志（排查首选手段）
docker compose up -d --build              # 改了代码后重新构建部署
docker compose down                       # 停止（数据保留在 scenic-mysql-data 卷）
docker compose down -v                    # 停止并清空数据卷（慎用：库里的数据会没）
```

## 七、常见问题

| 现象 | 原因与处理 |
|------|-----------|
| 首次构建卡在下载依赖 | 构建层已配阿里云/npmmirror 镜像，仍慢多为网络波动，重跑 `--build` 会续传缓存 |
| 构建卡死在拉取基础镜像（`maven:3.9-eclipse-temurin-21` / `node:20-slim` / `nginx:1.27` 等长时间无进度） | Docker Hub 国内直连慢。用国内镜像源拉取后重打官方名：`docker pull docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-21` → `docker tag docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-21 maven:3.9-eclipse-temurin-21`，其余镜像同理，然后重跑 compose |
| `.env` 报错 `MYSQL_ROOT_PASSWORD` 未设置 | deploy/ 目录缺 .env，按第二节从 .env.example 复制 |
| 后端起不来，日志连不上数据库 | `docker compose logs scenic-mysql` 看 MySQL 是否初始化失败（首次初始化需约 30 秒） |
| 数据想用 Navicat 看 | 取消 docker-compose.yml 里 scenic-mysql 的 ports 注释（映射到 3308），`docker compose up -d` 后连 localhost:3308 |

## 八、与规范的关系

- 组件版本遵循《项目代码规范与协作规范》§〇 环境与版本矩阵（MySQL 8.0 / Redis 7）
- 敏感信息（数据库密码）只存在于 `.env`（不入库），仓库内仅 `.env.example` 模板
