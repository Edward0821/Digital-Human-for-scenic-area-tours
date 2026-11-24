package com.scenic.flinkjob;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.AggregateFunction;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.assigners.SlidingProcessingTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 第十二周任务②：行为埋点 → Flink 用户兴趣画像 → Redis。
 *
 * 链路：Kafka topic `scenic-track`（消息 key = sessionId，保证同一会话进同一分区，窗口内有序）
 *   → 解析埋点 JSON（坏消息吞掉不中断作业）
 *   → keyBy(sessionId) → 滑动窗口按兴趣权重聚合 itemId
 *   → Redis Hash `scenic:profile:{sessionId}`（field=itemId, value=累计权重，TTL 默认 3 天）
 *
 * 兴趣权重口径（与接口契约 TRK-1/REC-1 对应）：
 *   EXPOSE=1 / CLICK=3 / STAY=停留分钟×2（上限 10）/ LIKE=5 / FAVORITE=8。
 * 第三步"在线打分"课设不做（老师原话：前两步必须做）——本作业即"第二步"。
 *
 * 跑法一（本地调试，默认直连宿主机 localhost:9092/6379）：
 *   java -jar target/flink-profile-job.jar
 * 跑法二（集群提交，deploy/docker-compose.flink.yml 起的 JobManager）：
 *   docker exec scenic-flink-jobmanager flink run -c com.scenic.flinkjob.TrackProfileJob /opt/flink-profile-job.jar \
 *     --bootstrap scenic-kafka:29092 --redis-host scenic-redis
 */
public class TrackProfileJob {

    private static final Logger LOG = LoggerFactory.getLogger(TrackProfileJob.class);

    public static void main(String[] args) throws Exception {
        ParameterTool params = ParameterTool.fromArgs(args);
        String bootstrap    = params.get("bootstrap", "localhost:9092");
        String redisHost    = params.get("redis-host", "127.0.0.1");
        int    redisPort    = params.getInt("redis-port", 6379);
        String redisPass    = params.get("redis-password", "pass4Redis");
        long   profileTtl   = params.getLong("ttl-sec", Duration.ofDays(3).toSeconds());
        int    windowSec    = params.getInt("window-sec", 600);  // 滑动窗口总长，默认 10 分钟
        int    slideSec     = params.getInt("slide-sec", 60);    // 滑动步长，默认 1 分钟
        int    topN         = params.getInt("top-n", 6);         // 每次窗口触发写入的 itemId 上限
        String topic        = params.get("topic", "scenic-track");

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1); // 单机课设规模，按会话分区后并行度 1 足够；扩容口子留在 keyBy 分区

        KafkaSource<String> source = KafkaSource.<String>builder()
                .setBootstrapServers(bootstrap)
                .setTopics(topic)
                .setGroupId("flink-profile-job")
                // 从已提交 offset 续跑；首次运行无 offset 则从最早开始（演示可复现）
                .setStartingOffsets(OffsetsInitializer.committedOffsets(
                        org.apache.kafka.clients.consumer.OffsetResetStrategy.EARLIEST))
                .setValueOnlyDeserializer(new SimpleStringSchema())
                .build();

        DataStream<ProfileEntry> profileStream = env
                .fromSource(source, WatermarkStrategy.noWatermarks(), "track-source")
                // 埋点失败/脏数据不阻塞作业（与 TRK-1"失败不阻塞"口径对齐）：解析失败的直接吞掉
                .flatMap(new EventParser()).name("parse-track-event")
                .keyBy(e -> e.sessionId)
                // processing time 语义：不信任客户端时间戳（web 安全第 19 关），服务端时间即可
                .window(SlidingProcessingTimeWindows.of(Time.seconds(windowSec), Time.seconds(slideSec)))
                .aggregate(new InterestAggregator(), new TopNWindower(topN))
                .name("interest-window");

        profileStream.addSink(new RedisProfileSink(redisHost, redisPort, redisPass, profileTtl))
                .name("redis-profile-sink");

        env.execute("scenic-track-profile-job");
    }

    // ---------- 数据结构 ----------

    /** 解析后的埋点事件（sessionId + 兴趣权重已在解析期打分） */
    public static class ProfileEntry {
        public String sessionId;
        public String itemId;
        public double weight;
    }

    /** keyBy 后每 session 的 itemId → 权重累计容器 */
    public static class InterestAccum {
        public Map<String, Double> byItem = new HashMap<>();
    }

    // ---------- 算子 ----------

    /** JSON → 打分事件；非法/非兴趣事件返回集合里跳过 */
    public static class EventParser implements org.apache.flink.api.common.functions.FlatMapFunction<String, ProfileEntry> {
        private static final ObjectMapper MAPPER = new ObjectMapper();
        private static final Map<String, Double> WEIGHTS = Map.of(
                "EXPOSE", 1.0, "CLICK", 3.0, "LIKE", 5.0, "FAVORITE", 8.0);

        @Override
        public void flatMap(String line, Collector<ProfileEntry> out) {
            try {
                JsonNode n = MAPPER.readTree(line);
                String type = n.path("event_type").asText("").toUpperCase();
                String sid = n.path("session_id").asText("");
                String itemId = n.path("item_id").asText("");
                if (sid.isEmpty() || itemId.isEmpty()) return;

                ProfileEntry e = new ProfileEntry();
                e.sessionId = sid;
                e.itemId = itemId;
                if ("STAY".equals(type)) {
                    long stayMs = n.path("stay_ms").asLong(0);
                    // 停留分钟数×2，单条上限 10（防一次异常停留刷爆权重）
                    e.weight = Math.min(stayMs / 60000.0 * 2.0, 10.0);
                } else {
                    e.weight = WEIGHTS.getOrDefault(type, 0.0);
                }
                if (e.weight > 0) out.collect(e);
            } catch (Exception ex) {
                LOG.warn("丢弃无法解析的埋点消息: {}", line);
            }
        }
    }

    /** 窗口内逐事件累加 itemId 权重 */
    public static class InterestAggregator implements AggregateFunction<ProfileEntry, InterestAccum, InterestAccum> {
        @Override
        public InterestAccum createAccumulator() { InterestAccum a = new InterestAccum(); a.byItem = new HashMap<>(); return a; }

        @Override
        public InterestAccum add(ProfileEntry e, InterestAccum acc) {
            acc.byItem.merge(e.itemId, e.weight, Double::sum);
            return acc;
        }

        @Override
        public InterestAccum getResult(InterestAccum acc) { return acc; }

        @Override
        public InterestAccum merge(InterestAccum a, InterestAccum b) {
            b.byItem.forEach((k, v) -> a.byItem.merge(k, v, Double::sum));
            return a;
        }
    }

    /** 窗口触发：按权重取 topN，逐条发往 Redis sink */
    public static class TopNWindower extends ProcessWindowFunction<InterestAccum, ProfileEntry, String, TimeWindow> {
        private final int topN;
        public TopNWindower(int topN) { this.topN = topN; }

        @Override
        public void process(String sessionId, Context ctx, Iterable<InterestAccum> in, Collector<ProfileEntry> out) {
            Map<String, Double> byItem = in.iterator().next().byItem;
            if (byItem.isEmpty()) return;
            List<Map.Entry<String, Double>> top = byItem.entrySet().stream()
                    .sorted(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue).reversed())
                    .limit(topN)
                    .collect(Collectors.toList());
            for (Map.Entry<String, Double> e : top) {
                ProfileEntry p = new ProfileEntry();
                p.sessionId = sessionId;
                p.itemId = e.getKey();
                p.weight = e.getValue();
                out.collect(p);
            }
        }
    }

    /** Redis Hash 写入：field=itemId, value=窗口权重（滑窗覆盖语义，老窗口值被最新窗口替换） */
    public static class RedisProfileSink extends RichSinkFunction<ProfileEntry> {
        private static final Logger LOG = LoggerFactory.getLogger(RedisProfileSink.class);
        private final String host;
        private final int port;
        private final String password;
        private final long ttlSec;
        private transient Jedis jedis;

        public RedisProfileSink(String host, int port, String password, long ttlSec) {
            this.host = host; this.port = port; this.password = password; this.ttlSec = ttlSec;
        }

        @Override
        public void open(Configuration parameters) {
            jedis = new Jedis(host, port);
            if (password != null && !password.isEmpty()) jedis.auth(password);
            jedis.select(0);
        }

        @Override
        public void invoke(ProfileEntry e, Context context) {
            // 同 key 连续写入：滑动窗口每次触发覆盖该 itemId 的最新权重（滑窗滚动 = 画像自然衰减/更新）
            jedis.hset("scenic:profile:" + e.sessionId, e.itemId, String.valueOf(Math.round(e.weight)));
            jedis.expire("scenic:profile:" + e.sessionId, (int) ttlSec);
            LOG.info("画像写入 Redis: session={} item={} weight={}", e.sessionId, e.itemId, e.weight);
        }

        @Override
        public void close() {
            if (jedis != null) jedis.close();
        }
    }
}
