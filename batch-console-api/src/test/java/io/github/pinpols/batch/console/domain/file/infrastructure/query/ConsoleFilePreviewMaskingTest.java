package io.github.pinpols.batch.console.domain.file.infrastructure.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.i18n.LocalizedErrorCarrier;
import io.github.pinpols.batch.common.i18n.LocalizedErrorRenderer;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.domain.file.application.contract.query.FileErrorRecordQueryRequest;
import io.github.pinpols.batch.console.domain.file.entity.FileErrorRecordEntity;
import io.github.pinpols.batch.console.domain.file.mapper.FileErrorRecordMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileRecordMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.file.support.ConsoleFileQueryMappers;
import io.github.pinpols.batch.console.domain.file.view.FileTemplateSecurityFlagsView;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("文件记录预览脱敏")
class ConsoleFilePreviewMaskingTest {

  private final FileErrorRecordMapper errorRecordMapper = mock(FileErrorRecordMapper.class);
  private final FileRecordMapper fileRecordMapper = mock(FileRecordMapper.class);
  private final FileTemplateConfigMapper templateConfigMapper =
      mock(FileTemplateConfigMapper.class);
  private final BatchSecurityProperties securityProperties = new BatchSecurityProperties();
  private final LocalizedErrorRenderer errorRenderer = mock(LocalizedErrorRenderer.class);
  private ConsoleFileQueryService service;

  @BeforeEach
  void setUp() {
    securityProperties.setBypassMode(false);
    when(errorRenderer.render(any(LocalizedErrorCarrier.class)))
        .thenAnswer(invocation -> ((LocalizedErrorCarrier) invocation.getArgument(0))
            .toLocalizedError()
            .renderedMessage());
    ConsoleFileQueryMappers mappers = new ConsoleFileQueryMappers(
        fileRecordMapper, null, errorRecordMapper, null, null, null, null, templateConfigMapper);
    TenantIdResolver tenantResolver = ignored -> "tenant-a";
    service = new ConsoleFileQueryService(
        tenantResolver,
        mappers,
        securityProperties,
        errorRenderer,
        mock(ConsoleOrchestratorPort.class));
    when(fileRecordMapper.selectTemplateCodeByFileId("tenant-a", 42L)).thenReturn("IMPORT");
    when(errorRecordMapper.selectByQuery(any()))
        .thenReturn(List.of(errorRecord("name=Alice phone=1234567890")));
    when(errorRecordMapper.countByQuery(any())).thenReturn(1L);
  }

  @Test
  @DisplayName("预览脱敏开启时只遮盖原始行内容")
  void shouldMaskRawRecordWhenPreviewMaskingIsEnabled() {
    when(templateConfigMapper.selectSecurityFlagsByTemplateCode("tenant-a", "IMPORT"))
        .thenReturn(FileTemplateSecurityFlagsView.builder()
            .previewMaskingEnabled(true)
            .errorLineMaskingEnabled(false)
            .maskingRuleSet("STRICT")
            .build());

    var response = service.fileErrorRecords(request()).items().getFirst();

    assertThat(response.rawRecord()).isEqualTo("name=*** phone=***");
    assertThat(response.errorMessage()).isEqualTo("invalid account 1234567890");
  }

  @Test
  @DisplayName("预览脱敏关闭时保留原始行内容")
  void shouldKeepRawRecordWhenPreviewMaskingIsDisabled() {
    when(templateConfigMapper.selectSecurityFlagsByTemplateCode("tenant-a", "IMPORT"))
        .thenReturn(FileTemplateSecurityFlagsView.builder()
            .previewMaskingEnabled(false)
            .errorLineMaskingEnabled(false)
            .maskingRuleSet("STRICT")
            .build());

    var response = service.fileErrorRecords(request()).items().getFirst();

    assertThat(response.rawRecord()).isEqualTo("name=Alice phone=1234567890");
    assertThat(response.errorMessage()).isEqualTo("invalid account 1234567890");
  }

  @Test
  @DisplayName("错误行脱敏仍同时遮盖错误消息和原始行")
  void shouldPreserveErrorLineMaskingBehavior() {
    when(templateConfigMapper.selectSecurityFlagsByTemplateCode("tenant-a", "IMPORT"))
        .thenReturn(FileTemplateSecurityFlagsView.builder()
            .previewMaskingEnabled(false)
            .errorLineMaskingEnabled(true)
            .maskingRuleSet("STRICT")
            .build());

    var response = service.fileErrorRecords(request()).items().getFirst();

    assertThat(response.rawRecord()).isEqualTo("name=*** phone=***");
    assertThat(response.errorMessage()).isEqualTo("invalid account ****");
  }

  private static FileErrorRecordEntity errorRecord(String rawRecord) {
    FileErrorRecordEntity entity = new FileErrorRecordEntity();
    entity.setTenantId("tenant-a");
    entity.setFileId(42L);
    entity.setErrorMessage("invalid account 1234567890");
    entity.setRawRecord(rawRecord);
    return entity;
  }

  private static FileErrorRecordQueryRequest request() {
    FileErrorRecordQueryRequest request = new FileErrorRecordQueryRequest();
    request.setTenantId("tenant-a");
    request.setFileId(42L);
    return request;
  }
}
