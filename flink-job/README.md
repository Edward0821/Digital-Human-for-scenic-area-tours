# flink-profile-job — 行为画像 Flink 作业（第十二周任务②骨架）

> 链路：前端埋点 → `TRK-1 POST /track`（组员实现）→ Kafka topic `scenic-track`（**消息 key = sessionId**，同一会话进同一分区）→ **本作业** → Redis Hash `scenic:profile:{sessionId}` → `REC-1 GET /recommend` 读。
> 老师口径："第三步在线打分可不做，**前两步（采集→Kafka→Flink→Redis）必须做**"。本作业即第二步。

## 兴趣权重口径（与契约 TRK-1/REC-1 对应）

| 事件 | 权重 | 说明 |
|---|---|---|
| EXPOSE | 1 | 曝光 |
| CLICK | 3 | 点击 |
| STAY | min(停留分钟×2, 10) | stay_ms 换算，单条封顶防刷 |
| LIKE | 5 | 点赞 |
| FAVORITE | 8 | 收藏 |

窗口：滑动窗口（默认 10 分钟总长 / 1 分钟步长，processing time 语义——**不信任客户端时间戳**）。

## 跑法一：本地调试（推荐开发用）

前置：Kafka（`deploy/docker-compose.kafka.yml`）、Redis（禅道包 6379/pass4Redis）在宿主机可达。

```powershell
# 1. 建 topic（幂等，按 sessionId 分区靠消息 key，分区数给 3）
docker exec scenic-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 `
  --create --if-not-exists --topic scenic-track --partitions 3 --replication-factor 1

# 2. 跑作业（jar 在 target/）
java -jar target/flink-profile-job.jar --window-sec 60 --slide-sec 10   # 调短实测用

# 3. 灌测试消息（key=sessionId；--property 让控制台生产者按 key 分区）
docker exec scenic-kafka bash -c "echo 'test-session-1|{\"event_type\":\"CLICK\",\"session_id\":\"test-session-1\",\"item_id\":\"101\"}' | /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic scenic-track --property parse.key=true --property key.separator=|"

# 4. 验证画像落 Redis（slide-sec=10 时约 10~20 秒出第一条）
& 'E:\javapractice\zentao\ZenTao\bin\redis\redis-cli.exe' -h 127.0.0.1 -p 6379 -a pass4Redis HGETALL scenic:profile:test-session-1
```

## 跑法二：Flink 集群提交（演示用）

```powershell
cd deploy
docker compose -f docker-compose.kafka.yml up -d
docker compose -f docker-compose.yml up -d scenic-redis
docker compose -f docker-compose.flink.yml up -d
# Web UI: http://localhost:8081
docker exec scenic-flink-jobmanager flink run -c com.scenic.flinkjob.TrackProfileJob `
  /opt/job-jar/flink-profile-job.jar --bootstrap scenic-kafka:29092 --redis-host scenic-redis
```

注意：集群内 Kafka 走 INTERNAL 监听器（`scenic-kafka:29092`），宿主机仍走 `localhost:9092`——双监听器互不影响。

## 常用参数

| 参数 | 默认 | 说明 |
|---|---|---|
| --bootstrap | localhost:9092 | Kafka 地址 |
| --redis-host / --redis-port / --redis-password | 127.0.0.1 / 6379 / pass4Redis | Redis 连接 |
| --window-sec / --slide-sec | 600 / 60 | 滑动窗口总长/步长（**演示/测试调短**） |
| --ttl-sec | 259200（3 天） | 画像键 TTL |
| --top-n | 6 | 每次窗口触发写入的 itemId 上限（对齐 REC-1 size=6） |
| --topic | scenic-track | 源 topic |

## 骨架边界（答辩口径）

- 单并行度即可承载课设规模；扩容口子：topic 按 sessionId 分区 + keyBy，天然可横向扩 TaskManager
- 没做 checkpoint 恢复演练（骨架允许），Web UI 已开 checkpoint 目录挂载
- Flink 挂了链路降级：埋点仍进 Kafka 不丢，作业恢复后从 committed offset 续算——"采集不丢，计算可恢复"
