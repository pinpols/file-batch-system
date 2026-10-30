package io.github.pinpols.batch.console.domain.file.infrastructure.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.i18n.LocalizedErrorRenderer;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
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
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;

@DisplayName("文件记录预览脱敏")
@ExtendWith(MockitoExtension.class)
class ConsoleFilePreviewMaskingTest {

  @Mock
  private FileErrorRecordMapper errorRecordMapper;

  @Mock
  private FileRecordMapper fileRecordMapper;

  @Mock
  private FileTemplateConfigMapper templateConfigMapper;

  private BatchSecurityProperties securityProperties;

  private ConsoleFileQueryService service;

  @BeforeEach
  void setUp() {
    securityProperties = new BatchSecurityProperties();
    securityProperties.setBypassMode(false);
    StaticMessageSource messages = new StaticMessageSource();
    messages.addMessage("test.localized", Locale.getDefault(), "localized account {0}");
    LocalizedErrorRenderer errorRenderer = new LocalizedErrorRenderer(messages, new ObjectMapper());
    ConsoleFileQueryMappers mappers = new ConsoleFileQueryMappers(
        fileRecordMapper, null, errorRecordMapper, null, null, null, null, templateConfigMapper);
    TenantIdResolver tenantResolver = ignored -> "tenant-a";
    service = new ConsoleFileQueryService(
        tenantResolver,
        mappers,
        securityProperties,
        errorRenderer,
        mock(ConsoleOrchestratorPort.class),
        mock(BatchDateTimeSupport.class));
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

  @Test
  @DisplayName("本地化后的错误消息仍遵循错误行脱敏策略")
  void shouldMaskLocalizedErrorMessageWhenErrorLineMaskingIsEnabled() {
    when(templateConfigMapper.selectSecurityFlagsByTemplateCode("tenant-a", "IMPORT"))
        .thenReturn(FileTemplateSecurityFlagsView.builder()
            .errorLineMaskingEnabled(true)
            .maskingRuleSet("STRICT")
            .build());
    FileErrorRecordEntity localized = errorRecord("name=Alice phone=1234567890");
    localized.setErrorKey("test.localized");
    localized.setErrorArgs("[\"1234567890\"]");
    when(errorRecordMapper.selectByQuery(any())).thenReturn(List.of(localized));

    var response = service.fileErrorRecords(request()).items().getFirst();

    assertThat(response.errorMessage()).isEqualTo("localized account ****");
  }

  @Test
  @DisplayName("未指定文件时每条错误记录仍遵循对应文件模板策略")
  void shouldApplyTemplateMaskingWhenFileIdIsNotSpecified() {
    when(templateConfigMapper.selectSecurityFlagsByTemplateCode("tenant-a", "IMPORT"))
        .thenReturn(FileTemplateSecurityFlagsView.builder()
            .errorLineMaskingEnabled(true)
            .maskingRuleSet("STRICT")
            .build());
    FileErrorRecordQueryRequest request = request();
    request.setFileId(null);

    var response = service.fileErrorRecords(request).items().getFirst();

    assertThat(response.rawRecord()).isEqualTo("name=*** phone=***");
    assertThat(response.errorMessage()).isEqualTo("invalid account ****");
  }

  @Test
  @DisplayName("混合文件列表分别使用各自策略并复用同文件策略查询")
  void shouldUseEachFilesPolicyInMixedResults() {
    FileErrorRecordEntity other = errorRecord("name=Alice phone=1234567890");
    other.setFileId(43L);
    when(errorRecordMapper.selectByQuery(any()))
        .thenReturn(List.of(
            errorRecord("name=Alice phone=1234567890"),
            other,
            errorRecord("name=Alice phone=1234567890")));
    when(fileRecordMapper.selectTemplateCodeByFileId("tenant-a", 43L)).thenReturn("OTHER");
    when(templateConfigMapper.selectSecurityFlagsByTemplateCode("tenant-a", "IMPORT"))
        .thenReturn(FileTemplateSecurityFlagsView.builder()
            .errorLineMaskingEnabled(true)
            .maskingRuleSet("STRICT")
            .build());
    when(templateConfigMapper.selectSecurityFlagsByTemplateCode("tenant-a", "OTHER"))
        .thenReturn(FileTemplateSecurityFlagsView.builder().build());
    FileErrorRecordQueryRequest request = request();
    request.setFileId(null);

    var items = service.fileErrorRecords(request).items();

    assertThat(items)
        .extracting(item -> item.rawRecord())
        .containsExactly("name=*** phone=***", "name=Alice phone=1234567890", "name=*** phone=***");
    verify(fileRecordMapper, times(1)).selectTemplateCodeByFileId("tenant-a", 42L);
    verify(fileRecordMapper, times(1)).selectTemplateCodeByFileId("tenant-a", 43L);
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
