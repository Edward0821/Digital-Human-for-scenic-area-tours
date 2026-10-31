package com.scenic.digitalhuman.controller;

import com.scenic.digitalhuman.common.ErrorCode;
import com.scenic.digitalhuman.common.Result;
import com.scenic.digitalhuman.service.FileStorageService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 文件模块 —— 对齐《接口设计说明书》模块 06（FILE）。
 * 大文件：init → 逐片 PUT（单片 md5 校验，坏片自动通知重传）→ complete（缺片核对 + 重组 + 整体终校）
 * 小文件：simple 直传，批量由前端多选并发调用
 * 下载：Range/206 断点续传 + 流式输出
 */
@Validated
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService fileStorageService;

    /** 上传初始化：秒传检查 / 断点续传接续 / 新建分片任务 */
    @PostMapping("/init")
    public Result<FileStorageService.InitResult> init(@Validated @RequestBody FileInitRequest req) {
        return Result.success(fileStorageService.init(
                req.fileName(), req.fileSize(), req.fileType(), req.md5(), req.chunkSize()));
    }

    /** 断点续传查询：返回已收分片号，前端只补缺失片 */
    @GetMapping("/{id}/progress")
    public Result<FileStorageService.InitResult> progress(@PathVariable long id) {
        return Result.success(fileStorageService.progress(id));
    }

    /** 上传单个分片：X-Chunk-Md5 为该分片内容的 md5，后端校验失败即通知前端重传该片 */
    @PutMapping("/{id}/chunks/{chunkNo}")
    public Result<Void> uploadChunk(@PathVariable long id,
                                    @PathVariable int chunkNo,
                                    @RequestHeader(value = "X-Chunk-Md5", required = false) String chunkMd5,
                                    @RequestBody byte[] body) {
        if (body == null || body.length == 0) {
            return Result.fail(ErrorCode.BAD_PARAM, "分片内容为空");
        }
        fileStorageService.saveChunk(id, chunkNo, body, chunkMd5);
        return Result.success();
    }

    /** 通知后台重组：缺片返回缺失清单；全齐则顺序合并 + 整体 md5 终校 */
    @PostMapping("/{id}/complete")
    public Result<String> complete(@PathVariable long id) {
        return Result.success(fileStorageService.complete(id));
    }

    /** 小文件（≤5MB）直传，批量上传时前端并发循环调用本接口 */
    @PostMapping("/simple")
    public Result<String> simple(@RequestParam("file") MultipartFile file) {
        return Result.success(fileStorageService.simpleUpload(file));
    }

    /** 下载：支持 Range（断点续传/多线程分段），流式输出不占堆内存 */
    @GetMapping("/{id}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable long id, HttpServletRequest request) {
        FileStorageService.DownloadResult dl =
                fileStorageService.download(id, request.getHeader(HttpHeaders.RANGE));
        String encodedName = URLEncoder.encode(dl.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName);
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.setContentLength(dl.contentLength());
        if (dl.status() == 206) {
            headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + dl.rangeStart() + "-" + dl.rangeEnd() + "/" + dl.totalSize());
        }
        return ResponseEntity.status(dl.status()).headers(headers).body(dl.body());
    }

    /** 初始化请求体 */
    public record FileInitRequest(
            @NotBlank(message = "文件名不能为空") String fileName,
            @NotNull(message = "文件大小不能为空") @Positive(message = "文件大小必须大于 0") Long fileSize,
            @NotBlank(message = "文件类型不能为空") String fileType,
            @NotBlank(message = "md5 不能为空") String md5,
            Long chunkSize) {
    }
}
