package io.github.pinpols.batch.console.domain.file.mapper;

import io.github.pinpols.batch.console.domain.file.query.FileRecordQuery;
import io.github.pinpols.batch.console.domain.file.view.FileRecordStorageView;
import io.github.pinpols.batch.console.domain.file.view.FileRecordSummaryView;
import io.github.pinpols.batch.console.domain.file.view.FileRecordView;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface FileRecordMapper {

  List<FileRecordView> selectByQuery(FileRecordQuery query);

  long countByQuery(FileRecordQuery query);

  String selectTemplateCodeByFileId(
      @Param("tenantId") String tenantId, @Param("fileId") Long fileId);

  FileRecordStorageView selectFileRecordById(
      @Param("tenantId") String tenantId, @Param("fileId") Long fileId);

  FileRecordSummaryView selectSummary(@Param("tenantId") String tenantId);
}
