package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.DataQualityRuleEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** ADR-021 数据对账规则 mapper。 */
public interface DataQualityRuleMapper {

  /** 按 (tenantId, scope_business_key 前缀匹配, enabled=true) 拉规则。 */
  List<DataQualityRuleEntity> selectEnabledByBusinessKey(
      @Param("tenantId") String tenantId, @Param("businessKey") String businessKey);

  DataQualityRuleEntity selectById(@Param("id") Long id);

  int insert(DataQualityRuleEntity entity);

  int update(DataQualityRuleEntity entity);
}
