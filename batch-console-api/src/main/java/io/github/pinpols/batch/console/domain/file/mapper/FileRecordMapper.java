package io.github.pinpols.batch.console.domain.file.mapper;

import io.github.pinpols.batch.console.domain.file.entity.FileRecordEntity;
import io.github.pinpols.batch.console.domain.file.query.FileRecordQuery;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface FileRecordMapper {

  List<FileRecordEntity> selectByQuery(FileRecordQuery query);

  long countByQuery(FileRecordQuery query);

  String selectTemplateCodeByFileId(
      @Param("tenantId") String tenantId, @Param("fileId") Long fileId);

  Map<String, Object> selectFileRecordById(
      @Param("tenantId") String tenantId, @Param("fileId") Long fileId);

  Map<String, Object> selectSummary(@Param("tenantId") String tenantId);
}
