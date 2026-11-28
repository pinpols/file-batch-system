package io.github.pinpols.batch.console.domain.job.infrastructure;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import io.github.pinpols.batch.common.utils.EmptyValues;
import io.github.pinpols.batch.console.domain.job.application.contract.request.JobDependencyRuleRequest;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;

/** 持久化依赖规则；所属作业服务会在提交事务后清除缓存。 */
@Component
@RequiredArgsConstructor
public class JobDependencyRuleWriter {

    private final JobDefinitionMapper jobDefinitionMapper;

    public void replace(String tenantId, String downstream, List<JobDependencyRuleRequest> rules) {
        Objects.requireNonNull(rules, "rules");
        jobDefinitionMapper.deleteDependencies(tenantId, downstream);
        if (EmptyValues.isNotEmpty(rules)) {
            jobDefinitionMapper.insertDependencyRules(tenantId, downstream, rules);
        }
    }
}
