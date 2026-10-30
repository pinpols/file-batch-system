package io.github.pinpols.batch.console.domain.job.infrastructure;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.domain.job.application.contract.request.JobDependencyRuleRequest;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 持久化依赖规则；所属作业服务会在提交事务后清除缓存。 */
@Component
@RequiredArgsConstructor
public class JobDependencyRuleWriter {

  private final JobDefinitionMapper jobDefinitionMapper;

  public void replace(String tenantId, String downstream, List<JobDependencyRuleRequest> rules) {
    Objects.requireNonNull(rules, "rules");
    jobDefinitionMapper.deleteDependencies(tenantId, downstream);
    if (EmptyChecks.isNotEmpty(rules)) {
      jobDefinitionMapper.insertDependencyRules(tenantId, downstream, rules);
    }
  }
}
