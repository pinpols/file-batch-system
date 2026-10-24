package io.github.pinpols.batch.console.domain.file.mapper;

import io.github.pinpols.batch.console.domain.file.param.FileTemplateConfigUpsertParam;
import io.github.pinpols.batch.console.domain.file.query.FileTemplateConfigQuery;
import io.github.pinpols.batch.console.domain.file.view.FileTemplateConfigQueryView;
import io.github.pinpols.batch.console.domain.file.view.FileTemplateConfigReadinessView;
import io.github.pinpols.batch.console.domain.file.view.FileTemplateSecurityFlagsView;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface FileTemplateConfigMapper {

  List<FileTemplateConfigQueryView> selectByQuery(@Param("q") FileTemplateConfigQuery q);

  long countByQuery(@Param("q") FileTemplateConfigQuery q);

  FileTemplateSecurityFlagsView selectSecurityFlagsByTemplateCode(
      @Param("tenantId") String tenantId, @Param("templateCode") String templateCode);

  Map<String, Object> selectByUniqueKey(
      @Param("tenantId") String tenantId,
      @Param("templateCode") String templateCode,
      @Param("version") Integer version);

  Map<String, Object> selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

  int toggleEnabled(
      @Param("tenantId") String tenantId, @Param("id") Long id, @Param("enabled") Boolean enabled);

  int deleteById(@Param("tenantId") String tenantId, @Param("id") Long id);

  int upsertFileTemplateConfig(@Param("p") FileTemplateConfigUpsertParam p);

  /**
   * 租户就绪自检专用:返回未删除模板的最小完整性字段 (templateCode, templateType, enabled, default_query_sql,
   * field_mappings, naming_rule)。只读,供 readiness 端点判定关键字段是否空占位。
   */
  List<FileTemplateConfigReadinessView> selectReadinessRows(@Param("tenantId") String tenantId);
}
