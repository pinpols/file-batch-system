package com.example.batch.console.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.exception.BizException;
import com.example.batch.console.domain.entity.ResourceTagEntity;
import com.example.batch.console.repository.ConsoleResourceTagRepository;
import com.example.batch.console.support.ConsoleTenantGuard;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConsoleResourceTagServiceTest {

  private ConsoleResourceTagRepository repository;
  private ConsoleTenantGuard tenantGuard;
  private ConsoleResourceTagService service;

  @BeforeEach
  void setUp() {
    repository = mock(ConsoleResourceTagRepository.class);
    tenantGuard = mock(ConsoleTenantGuard.class);
    service = new ConsoleResourceTagService(repository, tenantGuard);
    when(tenantGuard.resolveTenant("t1")).thenReturn("t1");
  }

  @Test
  void shouldListByResource() {
    ResourceTagEntity entity = new ResourceTagEntity();
    when(repository.findByResource("t1", "JOB", "job-001")).thenReturn(List.of(entity));

    List<ResourceTagEntity> result = service.listByResource("t1", "JOB", "job-001");

    assertThat(result).hasSize(1);
  }

  @Test
  void shouldListByTagKeyWithValue() {
    ResourceTagEntity entity = new ResourceTagEntity();
    when(repository.findByTagKeyAndValue("t1", "env", "prod")).thenReturn(List.of(entity));

    List<ResourceTagEntity> result = service.listByTagKey("t1", "env", "prod");

    assertThat(result).hasSize(1);
    verify(repository).findByTagKeyAndValue("t1", "env", "prod");
  }

  @Test
  void shouldListByTagKeyWithoutValue() {
    ResourceTagEntity entity = new ResourceTagEntity();
    when(repository.findByTagKey("t1", "env")).thenReturn(List.of(entity));

    List<ResourceTagEntity> result = service.listByTagKey("t1", "env", null);

    assertThat(result).hasSize(1);
    verify(repository).findByTagKey("t1", "env");
  }

  @Test
  void shouldListDistinctKeys() {
    when(repository.findDistinctTagKeys("t1")).thenReturn(List.of("env", "team"));

    List<String> result = service.listDistinctKeys("t1");

    assertThat(result).containsExactly("env", "team");
  }

  @Test
  void shouldUpsertTag() {
    service.upsert("t1", "JOB", "job-001", "env", "prod", "admin");

    verify(repository).upsert("t1", "JOB", "job-001", "env", "prod", "admin");
  }

  @Test
  void shouldDeleteTag() {
    service.delete("t1", "JOB", "job-001", "env");

    verify(repository).deleteByResourceAndKey("t1", "JOB", "job-001", "env");
  }

  @Test
  void shouldDeleteAllByResource() {
    service.deleteAllByResource("t1", "JOB", "job-001");

    verify(repository).deleteAllByResource("t1", "JOB", "job-001");
  }

  @Test
  void shouldRejectInvalidResourceType() {
    assertThatThrownBy(() -> service.upsert("t1", "UNKNOWN", "res-1", "key", "val", "admin"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("resourceType must be one of");
  }

  @Test
  void shouldNormalizeResourceTypeToUpperCase() {
    service.upsert("t1", "job", "job-001", "env", "prod", "admin");

    verify(repository).upsert("t1", "JOB", "job-001", "env", "prod", "admin");
  }

  @Test
  void shouldRejectBlankResourceType() {
    assertThatThrownBy(() -> service.upsert("t1", "  ", "res-1", "key", "val", "admin"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("resourceType is required");
  }
}
