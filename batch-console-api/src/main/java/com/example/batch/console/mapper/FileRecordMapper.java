package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.FileRecordEntity;
import com.example.batch.console.domain.query.FileRecordQuery;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface FileRecordMapper {

    List<FileRecordEntity> selectByQuery(FileRecordQuery query);

    long countByQuery(FileRecordQuery query);

    String selectTemplateCodeByFileId(
            @Param("tenantId") String tenantId, @Param("fileId") Long fileId);

    Map<String, Object> selectFileRecordById(
            @Param("tenantId") String tenantId, @Param("fileId") Long fileId);
}
