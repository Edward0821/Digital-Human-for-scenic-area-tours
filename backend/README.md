# digital-human-backend · 后端服务

景区导览服务 AI 数字人系统（第 7 组）后端工程。当前为项目脚手架，包含标准分包、统一响应、全局异常处理和健康检查接口。

## 技术栈

| 类别 | 选型 |
|------|------|
| 语言 / 运行时 | JDK 21 |
| 框架 | Spring Boot 3.5（Web + Validation） |
| ORM | MyBatis-Plus 3.5 |
| 数据库 | MySQL 8.0 |
| 缓存 / 向量库 | Redis（Redis Stack，后续承载向量检索） |
| 构建 | Maven |

## 环境要求

- JDK 21（Temurin）
- Maven 3.6.3+
- MySQL 8.0（本地或 Docker）
- Redis（可选：脚手架阶段未启动也能正常运行，健康检查会标记 DOWN）

## 快速启动

```bash
# 1. 初始化数据库（仓库根目录执行）
mysql -u root -p < mysql/init/01_create_db.sql

# 2. 修改数据库密码
#    backend/src/main/resources/application-dev.yml 中的 spring.datasource.password

# 3. 启动
cd backend
mvn spring-boot:run

# 4. 验证（浏览器或 curl）
curl http://localhost:8080/api/health
```

返回示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "app": "digital-human-backend",
    "status": "UP",
    "time": "2026-09-20 14:30:00",
    "components": { "mysql": "UP", "redis": "UP" }
  }
}
```

## 目录结构

```
backend
├── pom.xml
└── src/main
    ├── java/com/scenic/digitalhuman
    │   ├── DigitalHumanApplication.java   # 启动类
    │   ├── controller                     # 接口层
    │   ├── service                        # 业务层
    │   ├── mapper                         # 数据访问层
    │   ├── entity                         # 实体层（对应数据项字典）
    │   ├── config                         # MyBatis-Plus 分页、跨域等配置
    │   └── common                         # Result 统一响应、全局异常处理
    └── resources
        ├── application.yml                # 公共配置
        ├── application-dev.yml            # 本地开发（localhost）
        ├── application-docker.yml         # Docker 部署（compose 服务名）
        └── mapper                         # MyBatis XML
```

## 接口约定

- 统一前缀 `/api`
- 统一响应结构 `Result{code, message, data}`：code=200 成功、400 参数错误、500 系统错误
- 异常一律由 `GlobalExceptionHandler` 兜底，不向前端泄露堆栈

## 代码规范

- 遵循《项目代码规范与协作规范》（仓库 docs/ 目录）
- IDEA 安装 Alibaba Java Coding Guidelines 插件，提交前扫描通过

## 分支说明

本工程在 `backend` 分支开发，通过 PR 合入 `main`，不直接推送 `main`。
