package com.scenic.digitalhuman.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scenic.digitalhuman.common.BizException;
import com.scenic.digitalhuman.common.ErrorCode;
import com.scenic.digitalhuman.entity.SysFile;
import com.scenic.digitalhuman.mapper.SysFileMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 文件存储服务 —— 录屏讲解的五个核心问题都落在这一个类里：
 *
 * 1（分片/重组/坏块通知） saveChunk + complete
 * 2（小文件批量）        simpleUpload（接口本身单个，批量由前端并发循环调用）
 * 3（断点续传）          init 返回 uploadedChunks（续传只补缺失片）+ download 的 Range/206
 * 4（进度条）            后端提供 chunkTotal/uploadedChunks 与 Content-Length，进度由前端计算
 * 5（高并发下载）        接口无锁无状态 + 支持 Range 分段（可多线程并行拉取）+ 生产环境 Nginx sendfile
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    /** 类型白名单（sys_file.file_type 注释口径一致） */
    private static final Set<String> ALLOWED_TYPES = Set.of("pdf", "txt", "md", "docx");
    /** 小文件直传上限 5MB */
    private static final long SIMPLE_MAX_SIZE = 5L * 1024 * 1024;
    private static final String DEFAULT_CHUNK_SIZE = "5242880"; // 5MB

    private final SysFileMapper sysFileMapper;
    private final ObjectMapper objectMapper;

    @Value("${app.upload.dir:./uploads}")
    private String uploadDir;

    // ================= 1&3. 上传初始化：秒传检查 + 断点续传接续 =================

    public InitResult init(String fileName, long fileSize, String fileType, String md5, Long chunkSize) {
        if (md5 == null || !md5.matches("[0-9a-fA-F]{32}")) {
            throw new BizException(ErrorCode.BAD_FORMAT, "md5 必须为 32 位十六进制");
        }
        String ext = extensionOf(fileName);
        if (!ALLOWED_TYPES.contains(ext)) {
            throw new BizException(ErrorCode.BAD_FORMAT, "文件类型不在白名单：" + ALLOWED_TYPES);
        }
        long chunk = chunkSize == null || chunkSize <= 0 ? Long.parseLong(DEFAULT_CHUNK_SIZE) : chunkSize;
        int chunkTotal = (int) ((fileSize + chunk - 1) / chunk);

        // 秒传：同 md5 的文件已完成过，直接返回 url，不再传输一个字节（问题 1 附带能力）
        SysFile done = sysFileMapper.selectOne(new LambdaQueryWrapper<SysFile>()
                .eq(SysFile::getMd5, md5).eq(SysFile::getStatus, 1).last("LIMIT 1"));
        if (done != null) {
            return new InitResult(done.getId(), true, "/api/v1/files/" + done.getId() + "/download",
                    done.getFileName(), done.getChunkSize(), done.getChunkTotal(), done.getUploadedChunks());
        }

        // 断点续传：同 md5 且未传完 → 复用记录，前端只补 uploadedChunks 里缺的分片
        SysFile partial = sysFileMapper.selectOne(new LambdaQueryWrapper<SysFile>()
                .eq(SysFile::getMd5, md5).eq(SysFile::getStatus, 0).last("LIMIT 1"));
        if (partial != null) {
            return new InitResult(partial.getId(), false, null, partial.getFileName(),
                    partial.getChunkSize(), partial.getChunkTotal(), partial.getUploadedChunks());
        }

        // 全新上传
        SysFile file = new SysFile();
        file.setFileName(fileName);
        file.setFileSize(fileSize);
        file.setFileType(ext);
        file.setMd5(md5);
        file.setChunkSize(chunk);
        file.setChunkTotal(chunkTotal);
        file.setUploadedChunks(List.of());
        file.setStatus(0);
        // storage_path NOT NULL：任务未完成前无最终路径，先落占位值，complete 成功后由 markCompleted 覆盖
        file.setStoragePath("tmp/pending");
        sysFileMapper.insert(file);
        return new InitResult(file.getId(), false, null, fileName, chunk, chunkTotal, List.of());
    }

    /** 断点续传查询：返回已收分片号列表（前端刷新页面/重试前调用） */
    public InitResult progress(long id) {
        SysFile file = requireFile(id);
        return new InitResult(file.getId(), false, null, file.getFileName(),
                file.getChunkSize(), file.getChunkTotal(), file.getUploadedChunks());
    }

    // ================= 1. 分片上传：单片 md5 校验 + 原子登记 =================

    /**
     * 接收一个分片。请求头 X-Chunk-Md5 为该分片内容的 md5（前端计算）；
     * 后端落盘前重新计算比对——不一致说明分片在传输中损坏，返回 400 并指明分片号，前端自动重传该片。
     */
    public void saveChunk(long id, int chunkNo, byte[] body, String claimedChunkMd5) {
        SysFile file = requireFile(id);
        if (file.getStatus() != 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "文件不处于上传中状态");
        }
        if (chunkNo < 1 || chunkNo > file.getChunkTotal()) {
            throw new BizException(ErrorCode.BAD_FORMAT, "分片号越界：" + chunkNo);
        }
        // 分片级完整性校验：内容 md5 != 声称 md5 → 该片损坏，通知前端重传（问题 1 的"坏块通知"）
        String actualMd5 = DigestUtils.md5DigestAsHex(body);
        if (claimedChunkMd5 != null && !claimedChunkMd5.equalsIgnoreCase(actualMd5)) {
            throw new BizException(ErrorCode.BAD_PARAM, "第 " + chunkNo + " 片 md5 校验失败，请重传该片", chunkNo);
        }
        Path part = tmpDir(id).resolve(chunkNo + ".part");
        try {
            Files.createDirectories(part.getParent());
            Files.write(part, body);
        } catch (IOException e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "分片落盘失败");
        }
        // 原子登记：JSON_ARRAY_APPEND + JSON_CONTAINS 防重复，并发安全
        sysFileMapper.appendChunk(id, chunkNo);
    }

    // ================= 1. 后台重组：缺片核对 → 顺序合并 → 整体 md5 终校 =================

    @SuppressWarnings("unchecked")
    public String complete(long id) {
        SysFile file = requireFile(id);
        if (file.getStatus() == 1) {
            return "/api/v1/files/" + id + "/download"; // 幂等：重复 complete 直接返回 url
        }
        // ① 完整性核对：缺哪些片就明确告诉前端，前端只重传缺失的（问题 1 的第二层坏块通知）
        List<Integer> have = file.getUploadedChunks() == null ? List.of() : file.getUploadedChunks();
        Set<Integer> haveSet = Set.copyOf(have);
        List<Integer> missing = IntStream.rangeClosed(1, file.getChunkTotal())
                .filter(no -> !haveSet.contains(no)).boxed().toList();
        if (!missing.isEmpty()) {
            throw new BizException(ErrorCode.BAD_FORMAT, "分片不完整，缺失分片号见 data.missing", missing);
        }
        // ② 按分片号顺序合并 tmp 分片（后台重组）
        Path finalPath = finalPath(file.getFileName());
        String mergedMd5;
        try {
            Files.createDirectories(finalPath.getParent()); // FileChannel.open 不会自动建父目录
        } catch (IOException e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "创建目标目录失败");
        }
        try (FileChannel out = FileChannel.open(finalPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            for (int no = 1; no <= file.getChunkTotal(); no++) {
                byte[] part = Files.readAllBytes(tmpDir(id).resolve(no + ".part"));
                md5.update(part);
                out.write(ByteBuffer.wrap(part));
            }
            mergedMd5 = HexFormat.of().formatHex(md5.digest());
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "分片合并失败");
        }
        // ③ 整体 md5 终校：合并结果与 init 时声明的 md5 不一致 → 判定失败，要求重新上传
        if (!mergedMd5.equalsIgnoreCase(file.getMd5())) {
            cleanup(id, finalPath);
            sysFileMapper.markFailed(id);
            throw new BizException(ErrorCode.MERGE_MD5_MISMATCH, "合并后整体 md5 校验失败，请重新上传");
        }
        // ④ 登记完成
        String fullChunks = "[" + IntStream.rangeClosed(1, file.getChunkTotal())
                .mapToObj(String::valueOf).collect(Collectors.joining(",")) + "]";
        sysFileMapper.markCompleted(id, uploadDirRel(finalPath), fullChunks);
        return "/api/v1/files/" + id + "/download";
    }

    // ================= 2. 小文件直传（批量 = 前端多选后并发循环调用本接口） =================

    public String simpleUpload(MultipartFile file) {
        String ext = extensionOf(file.getOriginalFilename());
        if (!ALLOWED_TYPES.contains(ext)) {
            throw new BizException(ErrorCode.BAD_FORMAT, "文件类型不在白名单：" + ALLOWED_TYPES);
        }
        if (file.getSize() > SIMPLE_MAX_SIZE) {
            throw new BizException(ErrorCode.BAD_PAYLOAD, "小文件直传上限 5MB，请走分片上传");
        }
        String md5;
        Path finalPath = finalPath(file.getOriginalFilename());
        try (InputStream in = file.getInputStream()) {
            md5 = DigestUtils.md5DigestAsHex(in);
        } catch (IOException e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "读取上传内容失败");
        }
        try {
            Files.copy(file.getInputStream(), finalPath,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "落盘失败");
        }
        SysFile record = new SysFile();
        record.setFileName(file.getOriginalFilename());
        record.setStoragePath(uploadDirRel(finalPath));
        record.setFileSize(file.getSize());
        record.setFileType(ext);
        record.setMd5(md5);
        record.setChunkSize(file.getSize());
        record.setChunkTotal(1);
        record.setUploadedChunks(List.of(1));
        record.setStatus(1);
        sysFileMapper.insert(record);
        return "/api/v1/files/" + record.getId() + "/download";
    }

    // ================= 3&4&5. 下载：Range 断点续传 + 流式输出 =================

    public DownloadResult download(long id, String rangeHeader) {
        SysFile file = requireFile(id);
        if (file.getStatus() != 1) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "文件尚未上传完成");
        }
        Path path = Path.of(uploadDir).resolve(file.getStoragePath()).normalize();
        if (!Files.exists(path)) {
            throw new BizException(ErrorCode.NOT_FOUND, "文件已丢失，请重新上传");
        }
        long total;
        try {
            total = Files.size(path);
        } catch (IOException e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "读取文件失败");
        }
        long start = 0, end = total - 1;
        int status = 200;
        // Range 解析：bytes=start-end / bytes=start- → 206 断点续传下载（问题 3 的下载侧）
        if (rangeHeader != null && rangeHeader.matches("bytes=\\d+-\\d*")) {
            String[] seg = rangeHeader.substring(6).split("-", -1);
            start = Long.parseLong(seg[0]);
            if (!seg[1].isBlank()) end = Math.min(Long.parseLong(seg[1]), total - 1);
            status = 206;
            if (start > end || start >= total) {
                throw new BizException(ErrorCode.BAD_FORMAT, "Range 越界");
            }
        }
        long length = end - start + 1;
        Path p = path;
        // lambda 引用的局部变量必须是 effectively final，这里做一次拷贝
        final long fStart = start;
        final long fLength = length;
        // StreamingResponseBody：边读边写，不把整个文件读进内存（问题 5：低内存、可高并发）
        StreamingResponseBody body = out -> {
            try (InputStream in = Files.newInputStream(p)) {
                skipFully(in, fStart);
                copy(in, out, fLength);
            }
        };
        return new DownloadResult(status, file.getFileName(), total, start, end, length, body);
    }

    // ================= 内部工具 =================

    private SysFile requireFile(long id) {
        SysFile file = sysFileMapper.selectById(id);
        if (file == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "文件记录不存在");
        }
        return file;
    }

    private Path tmpDir(long fileId) {
        return Path.of(uploadDir, "tmp", String.valueOf(fileId));
    }

    private Path finalPath(String fileName) {
        String yyyyMM = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        String ext = extensionOf(fileName);
        return Path.of(uploadDir, yyyyMM, UUID.randomUUID() + "." + ext);
    }

    private String uploadDirRel(Path path) {
        return Path.of(uploadDir).relativize(path).toString().replace('\\', '/');
    }

    private String extensionOf(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            throw new BizException(ErrorCode.BAD_FORMAT, "文件名缺少扩展名");
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }

    private void cleanup(long fileId, Path finalPath) {
        try {
            if (Files.exists(finalPath)) {
                Files.delete(finalPath);
            }
            try (var paths = Files.walk(tmpDir(fileId))) {
                paths.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        } catch (IOException e) {
            log.warn("清理失败分片目录失败: {}", fileId, e);
        }
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long skipped = in.skip(n);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    break;
                }
                n--;
            } else {
                n -= skipped;
            }
        }
    }

    private static void copy(InputStream in, OutputStream out, long limit) {
        byte[] buf = new byte[64 * 1024];
        try {
            long remaining = limit;
            while (remaining > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) {
                    break;
                }
                out.write(buf, 0, n);
                remaining -= n;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ================= DTO =================

    public record InitResult(Long fileId, boolean instant, String url, String fileName,
                             Long chunkSize, Integer chunkTotal, List<Integer> uploadedChunks) {
        public InitResult {
            uploadedChunks = uploadedChunks == null ? new ArrayList<>() : uploadedChunks;
        }
    }

    public record DownloadResult(int status, String fileName, long totalSize,
                                 long rangeStart, long rangeEnd, long contentLength,
                                 StreamingResponseBody body) {
    }
}
