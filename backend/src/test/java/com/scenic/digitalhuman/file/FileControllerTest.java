package com.scenic.digitalhuman.file;

import com.scenic.digitalhuman.common.ErrorCode;
import com.scenic.digitalhuman.entity.SysRole;
import com.scenic.digitalhuman.entity.SysUser;
import com.scenic.digitalhuman.entity.SysUserRole;
import com.scenic.digitalhuman.mapper.SysRoleMapper;
import com.scenic.digitalhuman.mapper.SysUserMapper;
import com.scenic.digitalhuman.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FILE 模块接口测试（JUnit 5 + MockMvc，走真实 Spring 上下文与 MySQL）。
 *
 * 设计说明：
 * - @Transactional：每个用例结束自动回滚 sys_file/sys_user 数据，不留脏记录；
 *   但分片/合并是文件系统操作，回滚不了 → 临时目录 target/test-uploads 由每次用例前后清理。
 * - 测试用户走真实注册流程（POST /auth/register）再升级为 ADMIN（file:manage 权限所需），
 *   用邮箱验证码方式登录（测试直接读 Redis 验证码），全链路同生产。
 * - 统一断言口径：HTTP 200 + 业务四件套 code/message/data/trace_id。
 * - 这 8 个用例同时是安全设计的"可执行说明书"：
 *   用例 5 = 坏块通知重传契约；用例 6 = 缺片核对契约；用例 8 = Range 断点续传契约。
 */
@SpringBootTest(properties = "app.upload.dir=target/test-uploads")
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("dev")
class FileControllerTest {

    /** 30 字节随机内容：md5 每次不同，与库中历史记录（秒传/续传查询）天然隔离；chunkSize=10 → 3 片 */
    private static final int CHUNK_SIZE = 10;
    private static final String FILE_NAME = "smoke-test.txt";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private SysUserMapper userMapper;
    @Autowired
    private SysRoleMapper roleMapper;
    @Autowired
    private SysUserRoleMapper userRoleMapper;

    private String token;

    /** DB 侧由 @Transactional 回滚；分片/合并是文件系统操作，用临时目录 + 每次清理隔离 */
    @BeforeEach
    void setup() throws Exception {
        deleteTestUploads();
        // Redis 侧状态不随 @Transactional 回滚：清登录限流计数（8 个用例 × 登录会超 5 次/分阈值）
        java.util.Set<String> rateKeys = redis.keys("scenic:ratelimit:auth:*");
        if (rateKeys != null && !rateKeys.isEmpty()) {
            redis.delete(rateKeys);
        }
        loginAsAdmin();
    }

    @AfterEach
    void removeTestUploads() throws Exception {
        deleteTestUploads();
    }

    private void deleteTestUploads() throws Exception {
        Path dir = Path.of("target/test-uploads");
        if (Files.exists(dir)) {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    /**
     * 注册随机用户 → 升级 ADMIN（file:manage 权限所需）→ 邮箱验证码登录 → 缓存 token。
     * 全程走真实接口（register / email-code / login），仅"升级 ADMIN"一步直接写库（事务内回滚）。
     */
    private void loginAsAdmin() throws Exception {
        String username = "junit_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String email = username + "@test.local";
        String password = "Junit#Pass123";

        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s","nickname":"单测用户","email":"%s"}
                                """.formatted(username, password, email)))
                .andExpect(jsonPath("$.code").value(0));

        // 升级 ADMIN：sys_user_role + sys_user.role 快照同事务双写（同 PermissionService 口径）
        SysUser user = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getUsername, username));
        SysRole admin = roleMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysRole>()
                        .eq(SysRole::getRoleCode, "ADMIN"));
        SysUserRole ur = new SysUserRole();
        ur.setUserId(user.getId());
        ur.setRoleId(admin.getId());
        userRoleMapper.insert(ur);
        SysUser upgrade = new SysUser();
        upgrade.setId(user.getId());
        upgrade.setRole("ADMIN");
        userMapper.updateById(upgrade);

        // 发邮箱验证码（SMTP 未配置时服务端降级：验证码仅落 Redis + 日志）
        mvc.perform(post("/api/v1/auth/email-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","scene":"LOGIN"}
                                """.formatted(email)))
                .andExpect(jsonPath("$.code").value(0));

        String code = redis.opsForValue().get("scenic:emailcode:LOGIN:" + email);

        MvcResult loginResult = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginType":"EMAIL_CODE","email":"%s","emailCode":"%s"}
                                """.formatted(email, code)))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        String json = loginResult.getResponse().getContentAsString();
        token = json.replaceAll(".*\"tokenValue\":\"([^\"]+)\".*", "$1");
    }

    /** 受保护接口统一携带 Sa-Token（Authorization: Bearer） */
    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + token);
    }

    private static String md5(byte[] b) {
        return DigestUtils.md5DigestAsHex(b);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** 每个用例生成独立的 30 字节内容（避免同 md5 撞上库中已有记录触发秒传/状态冲突） */
    private static String content() {
        return "JUnit-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    /** 初始化一个分片上传任务，返回 fileId（测试内部构造数据用） */
    private long initTask(String payload) throws Exception {
        String body = """
                {"fileName":"%s","fileSize":%d,"fileType":"txt","md5":"%s","chunkSize":%d}
                """.formatted(FILE_NAME, payload.getBytes(StandardCharsets.UTF_8).length, md5(bytes(payload)), CHUNK_SIZE);
        MvcResult r = mvc.perform(auth(post("/api/v1/files/init"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        String json = r.getResponse().getContentAsString();
        return Long.parseLong(json.replaceAll(".*\"fileId\":(\\d+).*", "$1"));
    }

    /** 上传第 no 号分片（payload 切片），可选伪造 md5 头 */
    private MvcResult putChunk(long fileId, String payload, int no, String forgedMd5) throws Exception {
        byte[] all = bytes(payload);
        int from = (no - 1) * CHUNK_SIZE;
        int to = Math.min(no * CHUNK_SIZE, all.length);
        byte[] seg = java.util.Arrays.copyOfRange(all, from, to);
        return mvc.perform(auth(put("/api/v1/files/" + fileId + "/chunks/" + no))
                        .header("X-Chunk-Md5", forgedMd5 != null ? forgedMd5 : md5(seg))
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(seg))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
    }

    private void uploadAllChunks(long fileId, String payload) throws Exception {
        for (int no = 1; no <= 3; no++) putChunk(fileId, payload, no, null);
    }

    // ================= 用例 1：init 新建任务 =================

    @Test
    @DisplayName("init 新建任务：返回 fileId/chunkTotal=3/空已传列表")
    void init_createNewTask() throws Exception {
        String payload = content();
        mvc.perform(auth(post("/api/v1/files/init"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileName":"%s","fileSize":%d,"fileType":"txt","md5":"%s","chunkSize":10}
                                """.formatted(FILE_NAME, bytes(payload).length, md5(bytes(payload)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.instant").value(false))
                .andExpect(jsonPath("$.data.chunkTotal").value(3))
                .andExpect(jsonPath("$.data.chunkSize").value(10))
                .andExpect(jsonPath("$.data.uploadedChunks", hasSize(0)));
    }

    // ================= 用例 2：init 秒传命中 =================

    @Test
    @DisplayName("init 秒传：同 md5 完成过 → instant=true，不再建任务")
    void init_instantUpload() throws Exception {
        String payload = content();
        long id = initTask(payload);
        uploadAllChunks(id, payload);
        mvc.perform(auth(post("/api/v1/files/" + id + "/complete")))
                .andExpect(jsonPath("$.code").value(0));

        // 同内容再次 init → 秒传
        mvc.perform(auth(post("/api/v1/files/init"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileName":"%s","fileSize":%d,"fileType":"txt","md5":"%s","chunkSize":10}
                                """.formatted("smoke-test-2.txt", bytes(payload).length, md5(bytes(payload)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.instant").value(true))
                .andExpect(jsonPath("$.data.url", is("/api/v1/files/" + id + "/download")));
    }

    // ================= 用例 3：init 非法参数 =================

    @Test
    @DisplayName("init 参数校验：md5 非 32 位 hex → 10003")
    void init_rejectBadMd5() throws Exception {
        mvc.perform(auth(post("/api/v1/files/init"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileName":"%s","fileSize":30,"fileType":"txt","md5":"abc","chunkSize":10}
                                """.formatted(FILE_NAME)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.BAD_FORMAT))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    // ================= 用例 4：分片上传成功 =================

    @Test
    @DisplayName("分片上传：正确的 X-Chunk-Md5 → code=0")
    void chunk_uploadOk() throws Exception {
        String payload = content();
        long id = initTask(payload);
        putChunk(id, payload, 1, null); // putChunk 内部已断言 code=0
    }

    // ================= 用例 5：坏片被拒并通知重传（安全契约） =================

    @Test
    @DisplayName("坏片拒绝：内容与声称 md5 不一致 → 10001 且 data 指明分片号")
    void chunk_corruptedRejected() throws Exception {
        String payload = content();
        long id = initTask(payload);
        byte[] all = bytes(payload);
        byte[] seg1 = java.util.Arrays.copyOfRange(all, 0, 10);
        byte[] corrupted = seg1.clone();
        corrupted[0] ^= 0xff; // 模拟传输损坏 1 个字节

        mvc.perform(auth(put("/api/v1/files/" + id + "/chunks/1"))
                        .header("X-Chunk-Md5", md5(seg1)) // 声称的是原片 md5
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(corrupted))               // 实际是损坏内容
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.BAD_PARAM))
                .andExpect(jsonPath("$.data").value(1))    // 通知前端重传第 1 片
                .andExpect(jsonPath("$.message").value("第 1 片 md5 校验失败，请重传该片"));
    }

    // ================= 用例 6：complete 缺片核对（第二层坏块通知） =================

    @Test
    @DisplayName("complete 缺片：只传 2/3 片 → 10003 且 missing=[3]")
    void complete_reportsMissingChunks() throws Exception {
        String payload = content();
        long id = initTask(payload);
        putChunk(id, payload, 1, null);
        putChunk(id, payload, 2, null);

        mvc.perform(auth(post("/api/v1/files/" + id + "/complete")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.BAD_FORMAT))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0]").value(3));
    }

    // ================= 用例 7：complete 成功 =================

    @Test
    @DisplayName("complete 成功：全片就绪 → 返回下载 url")
    void complete_okAfterAllChunks() throws Exception {
        String payload = content();
        long id = initTask(payload);
        uploadAllChunks(id, payload);
        mvc.perform(auth(post("/api/v1/files/" + id + "/complete")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data", is("/api/v1/files/" + id + "/download")));
    }

    // ================= 用例 8：下载 Range 断点续传 =================

    @Test
    @DisplayName("下载 Range：bytes=5-14 → 206 + Content-Range + 10 字节")
    void download_range206() throws Exception {
        String payload = content();
        long id = initTask(payload);
        uploadAllChunks(id, payload);
        mvc.perform(auth(post("/api/v1/files/" + id + "/complete")))
                .andExpect(jsonPath("$.code").value(0));

        mvc.perform(auth(get("/api/v1/files/" + id + "/download")).header("Range", "bytes=5-14"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 5-14/30"))
                .andExpect(header().longValue("Content-Length", 10))
                .andExpect(MockMvcResultMatchers.content().string(payload.substring(5, 15)));
    }
}
