package io.github.pinpols.batch.console.domain.rbac.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.domain.rbac.entity.ResourceTagEntity;
import io.github.pinpols.batch.console.domain.rbac.mapper.ConsoleResourceTagMapper;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
  @DisplayName("写入标签按租户与资源维度下发下游")
  void shouldUpsertTag() {
    service.upsert("t1", "JOB", "job-001", "env", "prod", "admin");

    verify(repository).upsert("t1", "JOB", "job-001", "env", "prod", "admin");
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
    assertThatThrownBy(() -> service.upsert("t1", "UNKNOWN", "res-1", "key", "val", "admin"))
        .isInstanceOf(BizException.class)
        // i18n: messageKey 不含原文,改用 messageArgs 检查
        .satisfies(ex -> assertThat(((BizException) ex).getMessageArgs())
            .anyMatch(a -> a != null && a.toString().contains("resourceType must be one of")));
  }

  @Test
  @DisplayName("资源类型统一转为大写后写入下游")
  void shouldNormalizeResourceTypeToUpperCase() {
    service.upsert("t1", "job", "job-001", "env", "prod", "admin");

    verify(repository).upsert("t1", "JOB", "job-001", "env", "prod", "admin");
  }

  @Test
  @DisplayName("资源类型为空时抛出参数非法异常")
  void shouldRejectBlankResourceType() {
    assertThatThrownBy(() -> service.upsert("t1", "  ", "res-1", "key", "val", "admin"))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getMessageArgs())
            .anyMatch(a -> a != null && a.toString().contains("resourceType is required")));
  }
}
