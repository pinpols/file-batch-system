package io.github.pinpols.batch.console.infrastructure.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.console.application.rbac.ConsoleMetaQueryService;
import io.github.pinpols.batch.console.application.realtime.ConsoleRealtimeEventPort;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.domain.workflow.mapper.PipelineDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.PipelineStepDefinitionMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("流水线定义列表的应用层转换与 JSON 契约")
class PipelineDefinitionListContractTest {

  @Test
  @DisplayName("非空 Mapper 结果转换为 DTO，并保留租户过滤与 snake_case 响应")
  void shouldMapRowsToTypedItems_whenListContainsDefinitions() {
    PipelineDefinitionMapper mapper = mock(PipelineDefinitionMapper.class);
    ConsoleTenantGuard guard = mock(ConsoleTenantGuard.class);
    DefaultPipelineDefinitionService service = new DefaultPipelineDefinitionService(
        mapper,
        mock(PipelineStepDefinitionMapper.class),
        guard,
        mock(ConsoleRealtimeEventPort.class),
        mock(ConsoleMetaQueryService.class));
    Instant createdAt = Instant.parse("2026-10-07T00:00:00Z");
    when(guard.resolveTenant("ta")).thenReturn("ta");
    when(mapper.countByQuery("ta", "IMPORT_A", "IMPORT", true)).thenReturn(1L);
    when(mapper.selectByQuery(
            eq("ta"), eq("IMPORT_A"), eq("IMPORT"), eq(true), any(PageRequest.class)))
        .thenReturn(List.of(Map.of(
            "id",
            41L,
            "tenant_id",
            "ta",
            "job_code",
            "IMPORT_A",
            "pipeline_type",
            "IMPORT",
            "enabled",
            true,
            "version",
            2,
            "created_at",
            createdAt)));

    var page = service.list("ta", "IMPORT_A", "IMPORT", true, 1, 20);

    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items()).singleElement().satisfies(item -> {
      assertThat(item.id()).isEqualTo(41L);
      assertThat(item.tenantId()).isEqualTo("ta");
      assertThat(item.jobCode()).isEqualTo("IMPORT_A");
      assertThat(item.version()).isEqualTo(2);
      assertThat(item.createdAt()).isEqualTo(createdAt);
    });
    verify(mapper).selectByQuery("ta", "IMPORT_A", "IMPORT", true, new PageRequest(1, 20));
    String json = JsonMapper.builder().build().writeValueAsString(page);
    assertThat(json)
        .contains("\"tenant_id\":\"ta\"", "\"pipeline_type\":\"IMPORT\"")
        .doesNotContain("\"tenantId\"", "\"description\"");
  }
}
