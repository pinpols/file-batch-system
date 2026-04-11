package com.example.batch.orchestrator.service;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.persistence.entity.TriggerRequestEntity;
import com.example.batch.orchestrator.domain.entity.JobDefinitionRecord;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowDefinitionRecord;

/** 校验启动请求并加载所需定义，所有操作只读、无需事务。 从 {@link DefaultLaunchService} 中拆分以隔离校验关注点。 */
public interface LaunchValidationService {

    /** 校验请求字段，加载触发请求实体、Job 定义和工作流定义（未找到则置为 REJECTED 并抛异常）， 并检查是否存在重复实例。 */
    LaunchLoadResult load(LaunchRequest request);

    record LaunchLoadResult(
            TriggerRequestEntity triggerRequest,
            JobDefinitionRecord jobDefinition,
            WorkflowDefinitionRecord workflowDefinition,
            JobInstanceEntity existingInstance // 非空表示重复请求
            ) {}
}
