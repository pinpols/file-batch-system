package com.example.batch.console.mapper;

import com.example.batch.console.domain.param.FileTemplateConfigUpsertParam;
import com.example.batch.console.domain.query.FileTemplateConfigQuery;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface FileTemplateConfigMapper {

  List<Map<String, Object>> selectByQuery(@Param("q") FileTemplateConfigQuery q);

  long countByQuery(@Param("q") FileTemplateConfigQuery q);

  Map<String, Object> selectSecurityFlagsByTemplateCode(
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
}
