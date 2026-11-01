package io.github.pinpols.batch.orchestrator.application.service.dq;

import io.github.pinpols.batch.orchestrator.application.error.OperationFailure;
import io.github.pinpols.batch.orchestrator.application.error.OperationFailure.Kind;
import io.github.pinpols.batch.orchestrator.domain.command.DataQualityRuleWriteCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.DataQualityRuleEntity;
import io.github.pinpols.batch.orchestrator.mapper.DataQualityRuleMapper;
import java.util.List;
import java.util.Objects;
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
  public DataQualityRuleEntity get(String tenantId, Long id) {
    return ruleMapper.selectById(id, tenantId);
  }

  @Transactional
  public DataQualityRuleEntity create(String tenantId, DataQualityRuleWriteCommand command) {
    DataQualityRuleEntity rule = toEntity(command);
    // 与 update 同一纵深防御:租户来自受信调用上下文,不接受 body 覆盖。
    rule.setTenantId(tenantId);
    ruleMapper.insert(rule);
    DataQualityRuleEntity created = ruleMapper.selectById(rule.getId(), tenantId);
    return Objects.requireNonNull(created);
  }

  @Transactional
  public DataQualityRuleEntity update(
      String tenantId, Long id, DataQualityRuleWriteCommand command) {
    DataQualityRuleEntity rule = toEntity(command);
    rule.setId(id);
    // 纵深防御:以调用方断言的 tenantId 覆盖 body 声明,update SQL 的 WHERE 含 tenant_id → 跨租 id 改不到别人行。
    rule.setTenantId(tenantId);
    if (ruleMapper.update(rule) == 0) {
      throw new OperationFailure(Kind.NOT_FOUND, "Data quality rule was not found");
    }
    return Objects.requireNonNull(ruleMapper.selectById(id, tenantId));
  }

  private static DataQualityRuleEntity toEntity(DataQualityRuleWriteCommand command) {
    DataQualityRuleEntity rule = new DataQualityRuleEntity();
    rule.setId(command.id());
    rule.setTenantId(command.tenantId());
    rule.setRuleCode(command.ruleCode());
    rule.setRuleName(command.ruleName());
    rule.setRuleType(command.ruleType());
    rule.setScopeBusinessKey(command.scopeBusinessKey());
    rule.setExpression(command.expression());
    rule.setThresholdJson(command.thresholdJson());
    rule.setSeverity(command.severity());
    rule.setEnabled(command.enabled());
    rule.setDescription(command.description());
    rule.setCreatedAt(command.createdAt());
    rule.setUpdatedAt(command.updatedAt());
    return rule;
  }
}
