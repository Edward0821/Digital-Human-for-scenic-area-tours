package com.scenic.digitalhuman.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.scenic.digitalhuman.entity.SysFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SysFileMapper extends BaseMapper<SysFile> {

    /**
     * 原子追加分片号（并发上传同一片不会重复追加）：
     * 仅当记录仍处于"上传中"且该分片号尚未存在时才追加。
     */
    @Update("""
            UPDATE sys_file
               SET uploaded_chunks = JSON_ARRAY_APPEND(uploaded_chunks, '$', #{chunkNo})
             WHERE id = #{id}
               AND status = 0
               AND JSON_CONTAINS(uploaded_chunks, CAST(#{chunkNo} AS CHAR)) = 0
            """)
    int appendChunk(@Param("id") Long id, @Param("chunkNo") int chunkNo);

    /** 上传完成：写最终落盘路径 + 已收分片号对齐为完整序列 */
    @Update("""
            UPDATE sys_file
               SET storage_path = #{path}, status = 1, uploaded_chunks = #{chunksJson}
             WHERE id = #{id}
            """)
    int markCompleted(@Param("id") Long id, @Param("path") String path, @Param("chunksJson") String chunksJson);

    /** 标记上传失败（整体 md5 校验不过等场景） */
    @Update("UPDATE sys_file SET status = 2 WHERE id = #{id}")
    int markFailed(@Param("id") Long id);
}
