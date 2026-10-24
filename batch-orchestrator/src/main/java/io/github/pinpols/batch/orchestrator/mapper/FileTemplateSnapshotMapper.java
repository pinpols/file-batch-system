package io.github.pinpols.batch.orchestrator.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 以原生 JSON 读取完整选定模板，排除 JDBC 专用值类型。 */
@Mapper
public interface FileTemplateSnapshotMapper {

  String selectLatestConfiguration(
      @Param("tenantId") String tenantId,
      @Param("templateCode") String templateCode,
      @Param("templateType") String templateType);
}
