package io.github.pinpols.batch.console.domain.file.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.domain.file.application.FileTemplateMappingDraftCommand;
import io.github.pinpols.batch.console.domain.file.application.FileTemplateMappingDraftResult;
import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.file.param.FileTemplateConfigUpsertParam;
import io.github.pinpols.batch.console.domain.file.web.request.FileTemplateUpdateRequest;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultConsoleFileTemplateApplicationServiceTest {

  private final FileTemplateConfigMapper mapper = mock(FileTemplateConfigMapper.class);
  private final ConsoleTenantGuard tenantGuard = mock(ConsoleTenantGuard.class);
  private final ConsoleRequestMetadataResolver metadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private final DefaultConsoleFileTemplateApplicationService service =
      new DefaultConsoleFileTemplateApplicationService(
          mapper, tenantGuard, metadataResolver, new ObjectMapper());

  @BeforeEach
  void setUp() {
    when(metadataResolver.current())
        .thenReturn(
            new ConsoleRequestMetadata("req-1", "trace-1", "t1", "tester", "idem-1", "127.0.0.1"));
  }

  @Test
  void draftMapping_generatesImportTemplateJson() {
    when(tenantGuard.resolveTenant("t1")).thenReturn("t1");
    FileTemplateMappingDraftCommand command =
        new FileTemplateMappingDraftCommand(
            "t1",
            "IMPORT",
            "biz",
            "orders",
            null,
            List.of("order_no"),
            null,
            null,
            List.of(
                new FileTemplateMappingDraftCommand.Field(
                    "orderNo", "order_no", null, "STRING", true, null, null)));

    FileTemplateMappingDraftResult response = service.draftMapping(command);

    assertThat(response.direction()).isEqualTo("IMPORT");
    assertThat(response.fieldMappingsJson())
        .contains("\"name\":\"orderNo\"")
        .contains("\"targetColumn\":\"order_no\"")
        .contains("\"required\":true");
    assertThat(response.queryParamSchemaJson())
        .contains("\"jdbcMappedImport\"")
        .contains("\"schema\":\"biz\"")
        .contains("\"table\":\"orders\"")
        .contains("\"conflictColumns\":[\"order_no\"]");
    assertThat(response.warnings()).isEmpty();
  }

  @Test
  void update_shouldPersistPluginRefs() {
    when(tenantGuard.resolveTenant("t1")).thenReturn("t1");
    when(mapper.selectById("t1", 1L))
        .thenReturn(
            Map.ofEntries(
                Map.entry("template_code", "IMP-ORDER"),
                Map.entry("version", 1),
                Map.entry("template_name", "orders"),
                Map.entry("template_type", "IMPORT"),
                Map.entry("biz_type", "demo"),
                Map.entry("file_format_type", "DELIMITED"),
                Map.entry("charset", "UTF-8"),
                Map.entry("target_charset", "UTF-8"),
                Map.entry("with_bom", false),
                Map.entry("record_length", 0),
                Map.entry("header_rows", 1),
                Map.entry("footer_rows", 0),
                Map.entry("checksum_type", "NONE"),
                Map.entry("compress_type", "NONE"),
                Map.entry("encrypt_type", "NONE"),
                Map.entry("streaming_enabled", true),
                Map.entry("page_size", 1000),
                Map.entry("fetch_size", 1000),
                Map.entry("chunk_size", 500),
                Map.entry("preview_masking_enabled", false),
                Map.entry("error_line_masking_enabled", false),
                Map.entry("log_masking_enabled", false),
                Map.entry("content_encryption_enabled", false),
                Map.entry("download_requires_approval", false),
                Map.entry("enabled", true)));
    FileTemplateUpdateRequest request = new FileTemplateUpdateRequest();
    request.setTenantId("t1");
    request.setLoadTargetRef("jdbc_mapped");
    request.setExportDataRef("sql_template_export");

    service.update(1L, request);

    ArgumentCaptor<FileTemplateConfigUpsertParam> captor =
        ArgumentCaptor.forClass(FileTemplateConfigUpsertParam.class);
    verify(mapper).upsertFileTemplateConfig(captor.capture());
    assertThat(captor.getValue().getPluginRefs().getLoadTargetRef()).isEqualTo("jdbc_mapped");
    assertThat(captor.getValue().getPluginRefs().getExportDataRef())
        .isEqualTo("sql_template_export");
  }
}
