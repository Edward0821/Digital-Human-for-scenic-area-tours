package com.scenic.digitalhuman.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文件表实体（sys_file）—— 大文件分片上传 / 断点续传的落库依据。
 * uploaded_chunks：已收分片号 JSON 数组，断点续传时前端只补缺失的分片。
 */
@Data
@TableName(value = "sys_file", autoResultMap = true)
public class SysFile {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 原始文件名 */
    private String fileName;

    /** 落盘相对路径（uploads/yyyyMM/uuid.ext） */
    private String storagePath;

    /** 文件字节数 */
    private Long fileSize;

    /** 类型白名单（pdf/txt/md/docx） */
    private String fileType;

    /** 文件 MD5（秒传判断与完整性校验） */
    private String md5;

    /** 分片大小（字节，默认 5MB） */
    private Long chunkSize;

    /** 分片总数 */
    private Integer chunkTotal;

    /** 已收分片号数组（断点续传依据） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Integer> uploadedChunks;

    /** 状态（0 上传中 / 1 已完成 / 2 已失败） */
    private Integer status;

    private LocalDateTime createdAt;
}
