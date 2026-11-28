# QA-2/3/4 自测与答辩口径（第 7 组 · 第十二周追加任务·位 C）

> 分支：`feat/qa-crud`（提交 `fd0f6ff`，基于 main `e49da5f`）
> 契约依据：`接口清单-第7组.md` v1.4 模块 3 + 《第十二周追加任务分工-QA接口+大屏+语料.md》任务一
> 接口前缀：`/api/v1/qa`，统一响应 `code/message/data/traceId` 四件套

---

## 一、本次实现的三个接口

| # | 接口 | 方法 | 路径 | 鉴权 | 关键行为 |
|---|---|---|---|---|---|
| QA-2 | 满意度评价 | POST | `/records/{recordId}/feedback?sessionId=&satisfaction=` | 可选 | body `{"satisfaction":"GOOD\|BAD"}`；服务端归属校验防 IDOR；重复 409+20007 |
| QA-3 | 会话历史 | GET | `/records?sessionId=&page=&size=` | 可选 | 匿名按 session_id（idx_session_created），登录按 user_id（idx_created_at 时间窗+内存过滤）；`total+has_more+list` |
| QA-4 | 热门问题榜 | GET | `/hot-questions?week=&limit=` | 公开 | Redis ZSet `scenic:rank:hotq:{week}`，ISO 周号隔离，topN 问题文本+频次 |

> sessionId 两种携带方式都支持：查询参数 `?sessionId=xxx` 或请求头 `X-Session-Id: xxx`。

---

## 二、自测步骤（启动后端 + MySQL + Redis）

### QA-2 满意度评价

```bash
# 1. 正常评价（前提：qa_record 表里有 id=1 且属于该 session 的记录）
curl -X POST 'http://localhost:8080/api/v1/qa/records/1/feedback?sessionId=abc' \
  -H 'Content-Type: application/json' -d '{"satisfaction":"GOOD"}'
# 期望：{"code":0,"message":"success",...}

# 2. 重复评价 → 409 + 20007
curl -X POST '.../records/1/feedback?sessionId=abc' -H 'Content-Type: application/json' -d '{"satisfaction":"BAD"}'
# 期望：{"code":20007,"message":"该问答已评价，不能重复评价"}

# 3. 换一个 session 评价别人的记录 → 403 + 20003
curl -X POST '.../records/1/feedback?sessionId=OTHER_SESSION' -H 'Content-Type: application/json' -d '{"satisfaction":"GOOD"}'
# 期望：{"code":20003,"message":"无权评价该问答记录"}

# 4. 非法满意度 → 10003
curl -X POST '.../records/1/feedback?sessionId=abc' -H 'Content-Type: application/json' -d '{"satisfaction":"PERFECT"}'
# 期望：{"code":10003,"message":"满意度只能是 GOOD 或 BAD"}
```

### QA-3 会话历史

```bash
# 匿名带 sessionId（走 idx_session_created）
curl 'http://localhost:8080/api/v1/qa/records?sessionId=abc&page=1&size=10'
# 期望：{"code":0,"data":{"total":N,"has_more":false,"list":[{id,sessionId,question,answer,...}]}}

# 登录态（带 Sa-Token 令牌）按 user_id 查
curl 'http://localhost:8080/api/v1/qa/records?page=1&size=10' -H 'Authorization: Bearer <token>'

# 匿名不带 sessionId → 10001；非法分页 → 10001
curl 'http://localhost:8080/api/v1/qa/records'
# 期望：{"code":10001,"message":"匿名查询必须带 sessionId"}
```

### QA-4 热门问题榜

```bash
# 1. 连问 5 次同一个问题（QA-1 提问或 WS 通道均可，走 Kafka consumer 落库后计数）
# 2. 查本周榜（week 省略自动取当前 ISO 周号，如 2026-W48）
curl 'http://localhost:8080/api/v1/qa/hot-questions?limit=10'
# 期望：{"code":0,"data":{"week":"2026-W48","list":[{"rank":1,"question":"门票多少钱","count":5}]}}

# 3. 显式指定另一周 → 空列表（验证跨周 key 隔离）
curl 'http://localhost:8080/api/v1/qa/hot-questions?week=2020-W01'
# 期望：{"code":0,"data":{"week":"2020-W01","list":[]}}

# 4. Redis 侧核对 key 与 TTL
redis-cli KEYS 'scenic:rank:hotq:*'
redis-cli TTL scenic:rank:hotq:2026-W48   # 期望 0 < ttl <= 8*86400
```

---

## 三、答辩口径（背诵版）

### Q：QA-2 为什么要服务端校验归属？

> 这是水平越权（IDOR）防护的示范接口。前端传来的 `recordId` 不可信——攻击者改 URL 里的 id 就能评价甚至读到别人的问答记录。
> 服务端**不信任请求体里的 userId**：只认两种归属证据——① 登录态令牌解析出的 user_id 与 `record.user_id` 相等；② 游客态的 `session_id` 与 `record.session_id` 相等。
> 两者都不中 → 403+20003。匿名评价必须带 sessionId，否则连归属证据都没有，直接 10001。
> 并发兜底：`UPDATE ... WHERE id=? AND satisfaction IS NULL`，两个请求同时读到 NULL 时只有一个能写成功，抢不到的按 409 处理——单边校验挡不住并发竞态。

### Q：QA-3 登录态为什么按 user_id 查、不建索引？

> DDL 里 `qa_record` 刻意只建 `(session_id, created_at)` 和 `created_at` 索引，不建 `(user_id, created_at)`——避免写放大（每次插入都要维护额外 B+ 树）。
> 匿名查询走 `idx_session_created`，天然命中索引且有序；登录查询按 `user_id` 过滤时走 `idx_created_at` 的时间窗扫描后在内存里过滤 user_id。
> 课设数据千级规模，全扫代价可接受；答辩补一句：规模上量就加 `(user_id, created_at)` 联合索引。

### Q：QA-4 为什么用 Redis ZSet？跨周怎么隔离？

> ZSet 的 `ZINCRBY` 是单命令原子操作，提问高峰并发累加不会丢计数；`ZREVRANGE WITH SCORES` 一次取回问题文本+频次，不用回表查数据库。
> key 按 ISO 周号拼接 `scenic:rank:hotq:2026-W48`，每周自然翻页；整键 TTL 8 天，旧周数据自动过期不占内存。
> ISO 周用 `IsoFields.WEEK_OF_WEEK_BASED_YEAR` 计算，跨年那几天也不会算错周归属。

### Q：QA-4 写入侧挂在哪？为什么不影响问答主链路？

> 挂在 `QaRecordConsumer` 落库成功、推 answer 帧之后、`ack.acknowledge()` 之前——位于幂等去重之后，同一条消息重投不会重复计数。
> `QaHotQuestionService.recordQuestion()` 内部 try-catch 吞异常只记日志：榜单是统计副产品，Redis 挂了不能让已成功落库的问答卡在 ack 之前。

---

## 四、已知问题与处置

1. **契约清单笔误**：`接口清单-第7组.md` QA-2 条目写重复评价返回 `409+20006`，但同文档第五节码表中 20006=404 资源不存在、20007=409 状态冲突，且追加任务文档写 20007。本次按 **20007**（`ErrorCode.STATE_CONFLICT`）实现。建议组长顺手把清单那一行修正。
2. **测试环境**：`mvn test` 的 8 个错误来自既有 `FileControllerTest`，需要本机 MySQL（`DB_PASSWORD` 环境变量）+ Redis，与本次改动无关；`mvn -DskipTests compile` 已通过。
3. **git 流程**：本分支已 push，等组长 review 合入 main；不要自己合并或直推 main。
