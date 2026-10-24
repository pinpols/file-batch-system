package io.github.pinpols.batch.console.domain.rbac.service;

import io.github.pinpols.batch.console.domain.rbac.param.ConsoleResourceTagUpsertParam;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.domain.rbac.entity.ResourceTagEntity;
import io.github.pinpols.batch.console.domain.rbac.mapper.ConsoleResourceTagMapper;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@DisplayName("资源标签服务: 标签查询、写入、删除与资源类型规范化")
class ConsoleResourceTagServiceTest {

  private ConsoleResourceTagMapper repository;
  private ConsoleTenantGuard tenantGuard;
  private ConsoleResourceTagService service;

  @BeforeEach
  void setUp() {
    repository = mock(ConsoleResourceTagMapper.class);
    tenantGuard = mock(ConsoleTenantGuard.class);
    service = new ConsoleResourceTagService(repository, tenantGuard);
    when(tenantGuard.resolveTenant("t1")).thenReturn("t1");
  }

  @Test
  @DisplayName("按资源类型与资源标识查询标签, 返回命中的记录")
  void shouldListByResource() {
    ResourceTagEntity entity = new ResourceTagEntity();
    when(repository.findByResource("t1", "JOB", "job-001")).thenReturn(List.of(entity));

    List<ResourceTagEntity> result = service.listByResource("t1", "JOB", "job-001");

    assertThat(result).hasSize(1);
  }

  @Test
  @DisplayName("同时给定标签键与取值时按键值精确查询")
  void shouldListByTagKeyWithValue() {
    ResourceTagEntity entity = new ResourceTagEntity();
    when(repository.findByTagKeyAndValue("t1", "env", "prod")).thenReturn(List.of(entity));

    List<ResourceTagEntity> result = service.listByTagKey("t1", "env", "prod");

    assertThat(result).hasSize(1);
    verify(repository).findByTagKeyAndValue("t1", "env", "prod");
  }

  @Test
  @DisplayName("仅给定标签键时按键查询全部取值")
  void shouldListByTagKeyWithoutValue() {
    ResourceTagEntity entity = new ResourceTagEntity();
    when(repository.findByTagKey("t1", "env")).thenReturn(List.of(entity));

    List<ResourceTagEntity> result = service.listByTagKey("t1", "env", null);

    assertThat(result).hasSize(1);
    verify(repository).findByTagKey("t1", "env");
  }

  @Test
  @DisplayName("列出该租户下全部去重后的标签键")
  void shouldListDistinctKeys() {
    when(repository.findDistinctTagKeys("t1")).thenReturn(List.of("env", "team"));

    List<String> result = service.listDistinctKeys("t1");

    assertThat(result).containsExactly("env", "team");
  }

  @Test
  @DisplayName("写入标签前验证同租户资源确实存在")
  void shouldRejectTagForMissingResource() {
    when(repository.selectResourceTagTarget("t1", "JOB", "missing-job")).thenReturn(null);

    ResourceTagUpsertInput tagInput = ResourceTagUpsertInput.builder()
        .tenantId("t1")
        .resourceType("JOB")
        .resourceCode("missing-job")
        .tagKey("env")
        .tagValue("prod")
        .operator("admin")
        .build();
    assertThatThrownBy(() -> service.upsert(tagInput))
        .isInstanceOf(BizException.class);
    verify(repository).lockResourceTagIdentity("t1", "JOB", "missing-job");
    verify(repository, Mockito.never()).upsert(ConsoleResourceTagUpsertParam.builder()
        .tenantId("t1")
        .resourceType("JOB")
        .resourceCode("missing-job")
        .tagKey("env")
        .tagValue("prod")
        .operator("admin")
        .build());
  }

  @Test
  @DisplayName("写入标签按租户与资源维度下发下游")
  void shouldUpsertTag() {
    when(repository.lockResourceTagIdentity("t1", "JOB", "job-001")).thenReturn(1L);
    when(repository.selectResourceTagTarget("t1", "JOB", "job-001")).thenReturn(1L);

    ResourceTagUpsertInput tagInput2 = ResourceTagUpsertInput.builder()
        .tenantId("t1")
        .resourceType("JOB")
        .resourceCode("job-001")
        .tagKey("env")
        .tagValue("prod")
        .operator("admin")
        .build();
    service.upsert(tagInput2);

    verify(repository).lockResourceTagIdentity("t1", "JOB", "job-001");
    verify(repository).selectResourceTagTarget("t1", "JOB", "job-001");
    verify(repository).upsert(ConsoleResourceTagUpsertParam.builder()
        .tenantId("t1")
        .resourceType("JOB")
        .resourceCode("job-001")
        .tagKey("env")
        .tagValue("prod")
        .operator("admin")
        .build());
  }

  @Test
  @DisplayName("按资源与标签键删除单个标签")
  void shouldDeleteTag() {
    service.delete("t1", "JOB", "job-001", "env");

    verify(repository).deleteByResourceAndKey("t1", "JOB", "job-001", "env");
  }

  @Test
  @DisplayName("按资源维度删除该资源的全部标签")
  void shouldDeleteAllByResource() {
    service.deleteAllByResource("t1", "JOB", "job-001");

    verify(repository).deleteAllByResource("t1", "JOB", "job-001");
  }

  @Test
  @DisplayName("资源类型不在允许集合内时抛出参数非法异常, 并提示可选取值")
  void shouldRejectInvalidResourceType() {
    ResourceTagUpsertInput tagInput3 = ResourceTagUpsertInput.builder()
        .tenantId("t1")
        .resourceType("UNKNOWN")
        .resourceCode("res-1")
        .tagKey("key")
        .tagValue("val")
        .operator("admin")
        .build();
    assertThatThrownBy(() -> service.upsert(tagInput3))
        .isInstanceOf(BizException.class)
        // i18n: messageKey 不含原文,改用 messageArgs 检查
        .satisfies(ex -> assertThat(((BizException) ex).getMessageArgs())
            .anyMatch(a -> a != null && a.toString().contains("resourceType must be one of")));
  }

  @Test
  @DisplayName("资源类型统一转为大写后写入下游")
  void shouldNormalizeResourceTypeToUpperCase() {
    when(repository.selectResourceTagTarget("t1", "JOB", "job-001")).thenReturn(1L);
    ResourceTagUpsertInput tagInput4 = ResourceTagUpsertInput.builder()
        .tenantId("t1")
        .resourceType("job")
        .resourceCode("job-001")
        .tagKey("env")
        .tagValue("prod")
        .operator("admin")
        .build();
    service.upsert(tagInput4);

    verify(repository).upsert(ConsoleResourceTagUpsertParam.builder()
        .tenantId("t1")
        .resourceType("JOB")
        .resourceCode("job-001")
        .tagKey("env")
        .tagValue("prod")
        .operator("admin")
        .build());
  }

  @Test
  @DisplayName("资源类型为空时抛出参数非法异常")
  void shouldRejectBlankResourceType() {
    ResourceTagUpsertInput tagInput5 = ResourceTagUpsertInput.builder()
        .tenantId("t1")
        .resourceType("  ")
        .resourceCode("res-1")
        .tagKey("key")
        .tagValue("val")
        .operator("admin")
        .build();
    assertThatThrownBy(() -> service.upsert(tagInput5))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getMessageArgs())
            .anyMatch(a -> a != null && a.toString().contains("resourceType is required")));
  }

  @Test
  @DisplayName("缺省租户继续由授权守卫解析，空标签值规范为空串且操作人保持可空")
  void shouldResolveDefaultTenantAndNormalizeAbsentValue() {
    when(tenantGuard.resolveTenant(null)).thenReturn("t1");
    when(repository.selectResourceTagTarget("t1", "JOB", "job-001")).thenReturn(1L);
    ResourceTagUpsertInput tagInput = ResourceTagUpsertInput.builder()
        .resourceType("job")
        .resourceCode("job-001")
        .tagKey("env")
        .build();

    service.upsert(tagInput);

    ConsoleResourceTagUpsertParam expectedWrite = ConsoleResourceTagUpsertParam.builder()
        .tenantId("t1")
        .resourceType("JOB")
        .resourceCode("job-001")
        .tagKey("env")
        .tagValue("")
        .operator(null)
        .build();
    org.mockito.InOrder order = Mockito.inOrder(tenantGuard, repository);
    order.verify(tenantGuard).resolveTenant(null);
    order.verify(repository).lockResourceTagIdentity("t1", "JOB", "job-001");
    order.verify(repository).selectResourceTagTarget("t1", "JOB", "job-001");
    order.verify(repository).upsert(expectedWrite);
  }

  @Test
  @DisplayName("原始输入不提前校验，租户授权失败仍优先于资源字段错误")
  void shouldPreserveTenantGuardFailureBeforeResourceValidation() {
    BizException tenantFailure = BizException.of(ResultCode.FORBIDDEN, "error.tenant.context_missing");
    when(tenantGuard.resolveTenant(null)).thenThrow(tenantFailure);
    ResourceTagUpsertInput tagInput = ResourceTagUpsertInput.builder()
        .resourceType("  ")
        .resourceCode("")
        .tagKey("env")
        .build();

    assertThatThrownBy(() -> service.upsert(tagInput)).isSameAs(tenantFailure);

    Mockito.verifyNoInteractions(repository);
  }
}
