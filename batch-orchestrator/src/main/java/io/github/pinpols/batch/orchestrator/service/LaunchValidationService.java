package io.github.pinpols.batch.orchestrator.service;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowDefinitionEntity;

/**
 * 校验启动请求并加载所需定义，所有操作只读、无需事务。 从 {@link DefaultLaunchService} 中拆分以隔离校验关注点。
 *
 * <p>人工评审记录(2026-05-23):审计曾标记为单实现接口候选删除。 但本接口承载的嵌套 record {@code LaunchLoadResult} 被外部调用方以 {@code
 * LaunchValidationService.LaunchLoadResult} 形式广泛引用 (BatchDayGateService / LaunchBatchDayService /
 * ChildJobLaunchSupport 等), 直接合并接口与实现需要全仓 mass rename, 保留接口待人工评估后再处理。
 */
public interface LaunchValidationService {

  /** 校验请求字段，加载触发请求实体、Job 定义和工作流定义（未找到则置为 REJECTED 并抛异常）， 并检查是否存在重复实例。 */
  LaunchLoadResult load(LaunchRequest request);

  record LaunchLoadResult(
      TriggerRequestEntity triggerRequest,
      JobDefinitionEntity jobDefinition,
      WorkflowDefinitionEntity workflowDefinition,
      JobInstanceEntity existingInstance // 非空表示重复请求
      ) {}
}
