package io.github.pinpols.batch.orchestrator.application.service.dq;

import io.github.pinpols.batch.orchestrator.domain.entity.DataQualityRuleEntity;
import io.github.pinpols.batch.orchestrator.mapper.DataQualityRuleMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** DQ 规则应用服务：把内部 Controller 与 MyBatis Mapper 隔离，后续审计、权限和边界校验只在这里扩展。 */
@Service
@RequiredArgsConstructor
public class DataQualityRuleApplicationService {

  private final DataQualityRuleMapper ruleMapper;

  @Transactional(readOnly = true)
  public List<DataQualityRuleEntity> listEnabledByBusinessKey(String tenantId, String businessKey) {
    return ruleMapper.selectEnabledByBusinessKey(tenantId, businessKey);
  }

  @Transactional(readOnly = true)
  public DataQualityRuleEntity get(Long id) {
    return ruleMapper.selectById(id);
  }

  @Transactional
  public DataQualityRuleEntity create(DataQualityRuleEntity rule) {
    ruleMapper.insert(rule);
    return rule;
  }

  @Transactional
  public DataQualityRuleEntity update(Long id, DataQualityRuleEntity rule) {
    rule.setId(id);
    ruleMapper.update(rule);
    return rule;
  }
}
