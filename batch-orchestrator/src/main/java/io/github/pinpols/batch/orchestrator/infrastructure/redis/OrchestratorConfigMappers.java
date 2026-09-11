package io.github.pinpols.batch.orchestrator.infrastructure.redis;

import io.github.pinpols.batch.orchestrator.mapper.BatchWindowMapper;
import io.github.pinpols.batch.orchestrator.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.TenantQuotaPolicyMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowDefinitionMapper;
import org.springframework.stereotype.Component;

/**
 * Orchestrator 配置缓存使用的 MyBatis 读取端口集合。
 *
 * <p>这些 mapper 共同服务于同一个配置缓存边界，集中注入可以避免缓存服务随配置类型增加而持续扩大构造器，同时不额外引入只做转发的服务层。
 */
@Component
public record OrchestratorConfigMappers(
    JobDefinitionMapper jobDefinition,
    WorkflowDefinitionMapper workflowDefinition,
    BusinessCalendarMapper businessCalendar,
    BatchWindowMapper batchWindow,
    TenantQuotaPolicyMapper tenantQuotaPolicy) {}
