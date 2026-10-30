package io.github.pinpols.batch.orchestrator.infrastructure.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.orchestrator.mapper.AdminTestDataCleanupMapper;
import io.github.pinpols.batch.orchestrator.mapper.AdminTestDataCleanupMapper.CleanupTarget;
import io.github.pinpols.batch.orchestrator.mapper.AdminTestDataCleanupMapper.OutboxEventIdentity;
import io.github.pinpols.batch.orchestrator.mapper.AdminTestDataCleanupMapper.PrefixCleanupScope;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("测试数据清理仓储的前缀删除与精确租户删除行为,覆盖删除链目标,返回值汇总与租户入参校验")
@ExtendWith(MockitoExtension.class)
class AdminTestDataCleanupRepositoryTest {

  @Mock
  private AdminTestDataCleanupMapper mapper;

  private AdminTestDataCleanupRepository repository;

  @BeforeEach
  void setUp() {
    repository = new AdminTestDataCleanupRepository(mapper);
  }

  @Test
  @DisplayName("合法前缀触发完整外键删除链,汇总返回各删除目标的影响行数且父引用清理项不进入结果")
  void shouldRunFullFkDeletionChainOnValidPrefix() {
    when(mapper.cleanupByPrefix(any(), any())).thenReturn(1);

    var result = repository.cleanupByPrefix("e2e");

    assertThat(result)
        .containsEntry("trigger_request", 1)
        .containsEntry("trigger_outbox_event", 1)
        .containsEntry("outbox_event", 1)
        .containsEntry("workflow_node_run", 1)
        .containsEntry("compensation_command", 1)
        .containsEntry("approval_command", 1)
        .containsEntry("job_instance", 1)
        .containsEntry("api_key", 1)
        .containsEntry("alert_routing_config", 1)
        .containsEntry("tenant_quota_policy", 1)
        .doesNotContainKey("clear_job_instance_parent");
    PrefixCleanupScope scope =
        new PrefixCleanupScope("e2e-%", "op-e2e-%", List.of(), List.of(), List.of());
    verify(mapper).cleanupByPrefix(CleanupTarget.CLEAR_JOB_INSTANCE_PARENT, scope);
  }

  @Test
  @DisplayName("清理前缀仅作为绑定参数传入并在末尾追加通配符,不拼接进语句文本")
  void shouldPassEscapedPrefixOnlyAsBoundValue() {
    ArgumentCaptor<PrefixCleanupScope> scopeCaptor =
        ArgumentCaptor.forClass(PrefixCleanupScope.class);

    repository.cleanupByPrefix("test");

    verify(mapper).cleanupByPrefix(eq(CleanupTarget.JOB_INSTANCE), scopeCaptor.capture());
    PrefixCleanupScope scope = scopeCaptor.getValue();
    assertThat(scope.prefixLike()).isEqualTo("test-%");
    assertThat(scope.operatorPrefixLike()).isEqualTo("op-test-%");
  }

  @Test
  @DisplayName("删除前固定运行请求及完整Outbox分区身份,按外键依赖顺序清理且事件删除后仍能清理幂等键")
  void shouldCaptureOwnedIdentitiesBeforeDeletingSources() {
    PrefixCleanupScope discovery =
        new PrefixCleanupScope("e2e-%", "op-e2e-%", List.of(), List.of(), List.of());
    List<Long> runIds = List.of(101L);
    List<Long> requestIds = List.of(201L, 202L);
    PrefixCleanupScope runScope =
        new PrefixCleanupScope("e2e-%", "op-e2e-%", runIds, List.of(), List.of());
    PrefixCleanupScope requestScope =
        new PrefixCleanupScope("e2e-%", "op-e2e-%", runIds, requestIds, List.of());
    OffsetDateTime createdAt = OffsetDateTime.parse("2026-10-24T00:00:00Z");
    OutboxEventIdentity event =
        new OutboxEventIdentity("owned-tenant", 301L, createdAt, "owned-event");
    List<OutboxEventIdentity> events = List.of(event);
    PrefixCleanupScope frozenScope =
        new PrefixCleanupScope("e2e-%", "op-e2e-%", runIds, requestIds, events);
    when(mapper.findWorkflowRunIdsByPrefix(discovery)).thenReturn(runIds);
    when(mapper.findTriggerRequestIdsByPrefix(runScope)).thenReturn(requestIds);
    when(mapper.findOutboxEventsByPrefix(requestScope)).thenReturn(events);

    repository.cleanupByPrefix("e2e");

    InOrder ordered = inOrder(mapper);
    ordered.verify(mapper).findWorkflowRunIdsByPrefix(discovery);
    ordered.verify(mapper).findTriggerRequestIdsByPrefix(runScope);
    ordered.verify(mapper).findOutboxEventsByPrefix(requestScope);
    ordered.verify(mapper).hasAmbiguousJobInstanceIds(frozenScope);
    ordered.verify(mapper).cleanupByPrefix(CleanupTarget.EVENT_DELIVERY_LOG, frozenScope);
    ordered.verify(mapper).cleanupByPrefix(CleanupTarget.EVENT_OUTBOX_RETRY, frozenScope);
    ordered.verify(mapper).cleanupByPrefix(CleanupTarget.OUTBOX_EVENT, frozenScope);
    ordered.verify(mapper).cleanupByPrefix(CleanupTarget.OUTBOX_EVENT_DEDUP_KEY, frozenScope);
    ordered.verify(mapper).cleanupByPrefix(CleanupTarget.WORKFLOW_NODE_RUN, frozenScope);
    ordered.verify(mapper).cleanupByPrefix(CleanupTarget.WORKFLOW_RUN, frozenScope);
    ordered.verify(mapper).cleanupByPrefix(CleanupTarget.JOB_INSTANCE, frozenScope);
    ordered.verify(mapper).cleanupByPrefix(CleanupTarget.TRIGGER_OUTBOX_EVENT, frozenScope);
    ordered.verify(mapper).cleanupByPrefix(CleanupTarget.TRIGGER_REQUEST, frozenScope);
    ordered.verify(mapper).cleanupByPrefix(CleanupTarget.WORKFLOW_DEFINITION, frozenScope);
  }

  @Test
  @DisplayName("依赖删除失败时传播原异常并停止后续删除,不返回伪成功结果")
  void shouldStopDeletionChain_whenOutboxChildDeletionFails() {
    PrefixCleanupScope scope =
        new PrefixCleanupScope("e2e-%", "op-e2e-%", List.of(), List.of(), List.of());
    IllegalStateException failure = new IllegalStateException("outbox child deletion failed");
    doAnswer(invocation -> {
          if (invocation.getArgument(0) == CleanupTarget.EVENT_OUTBOX_RETRY) {
            throw failure;
          }
          return 0;
        })
        .when(mapper)
        .cleanupByPrefix(any(), eq(scope));

    assertThatThrownBy(() -> repository.cleanupByPrefix("e2e")).isSameAs(failure);

    verify(mapper, never()).cleanupByPrefix(CleanupTarget.OUTBOX_EVENT, scope);
    verify(mapper, never()).cleanupByPrefix(CleanupTarget.WORKFLOW_DEFINITION, scope);
  }

  @Test
  @DisplayName("所选实例数字ID存在范围外热记录时返回冲突且不执行任何删除")
  void shouldRejectBeforeAnyDeletion_whenInstanceIdentityAmbiguous() {
    PrefixCleanupScope scope =
        new PrefixCleanupScope("e2e-%", "op-e2e-%", List.of(), List.of(), List.of());
    when(mapper.hasAmbiguousJobInstanceIds(scope)).thenReturn(true);

    assertThatThrownBy(() -> repository.cleanupByPrefix("e2e"))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).getCode())
        .isEqualTo(ResultCode.CONFLICT);

    verify(mapper, never()).cleanupByPrefix(any(), any());
  }

  @Test
  @DisplayName("按精确租户标识清理时覆盖全部删除目标并汇总结果,父引用清理项不进入返回结果")
  void shouldRunExactTenantCleanupTargets() {
    when(mapper.cleanupByTenantIds(any(), any())).thenReturn(1);

    var result = repository.cleanupByExactTenantIds(List.of("td"));

    assertThat(result)
        .containsEntry("pipeline_step_run", 1)
        .containsEntry("file_error_record", 1)
        .containsEntry("tenant", 1)
        .doesNotContainKey("clear_job_instance_parent");
    verify(mapper).cleanupByTenantIds(CleanupTarget.CLEAR_JOB_INSTANCE_PARENT, List.of("td"));
  }

  @Test
  @DisplayName("租户标识列表为空时拒绝清理并返回参数非法错误码")
  void shouldRejectMissingTenantIds() {
    assertThatThrownBy(() -> repository.cleanupByExactTenantIds(List.of()))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).getCode())
        .isEqualTo(ResultCode.INVALID_ARGUMENT);
  }

  @Test
  @DisplayName("包含受保护的系统租户标识时拒绝清理并返回参数非法错误码")
  void shouldRejectProtectedTenantIds() {
    assertThatThrownBy(() -> repository.cleanupByExactTenantIds(List.of("system")))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).getCode())
        .isEqualTo(ResultCode.INVALID_ARGUMENT);
  }
}
