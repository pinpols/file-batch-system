package io.github.pinpols.batch.console.domain.file.infrastructure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.domain.file.application.contract.request.FileTemplateCreateRequest;
import io.github.pinpols.batch.console.domain.file.application.contract.request.FileTemplateUpdateRequest;
import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.file.view.FileTemplateConfigQueryView;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("导入模板加密类型的写入校验")
class ImportEncryptTypeTemplateValidationTest {

  @Mock
  FileTemplateConfigMapper mapper;

  @Mock
  ConsoleTenantGuard tenantGuard;

  @Mock
  ConsoleRequestMetadataResolver metadataResolver;

  private DefaultConsoleFileTemplateApplicationService service;

  @BeforeEach
  void setUp() {
    service = new DefaultConsoleFileTemplateApplicationService(
        mapper, tenantGuard, metadataResolver, new ObjectMapper());
    when(tenantGuard.resolveTenant("t1")).thenReturn("t1");
    when(metadataResolver.current())
        .thenReturn(
            new ConsoleRequestMetadata("req", "trace", "t1", "tester", "idem", "127.0.0.1"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"PGP", "CUSTOM", "pgp"})
  @DisplayName("创建启用的导入模板时拒绝运行时不支持的加密类型")
  void shouldRejectUnsupportedEncryptType_whenCreatingEnabledImportTemplate(String encryptType) {
    FileTemplateCreateRequest request = createRequest("IMPORT", true, encryptType);
    assertThatThrownBy(() -> service.create(request)).isInstanceOf(BizException.class);
    verify(mapper, never()).upsertFileTemplateConfig(any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"NONE", "AES"})
  @DisplayName("创建启用的导入模板时允许 NONE 与 AES，且不查询流水线")
  void shouldAllowImplicitlySupportedEncryptType(String encryptType) {
    service.create(createRequest("IMPORT", true, encryptType));
    verify(mapper).upsertFileTemplateConfig(any());
    verify(mapper, never()).hasPreprocessPipeline(anyString(), anyString(), anyInt());
  }

  @Test
  @DisplayName("未填写加密类型时按默认值放行")
  void shouldAllowBlankEncryptType() {
    service.create(createRequest("IMPORT", true, null));
    verify(mapper).upsertFileTemplateConfig(any());
  }

  @Test
  @DisplayName("导出模板与未启用草稿不受影响")
  void shouldAllowExportTemplateAndDisabledDraft() {
    service.create(createRequest("EXPORT", true, "PGP"));
    service.create(createRequest("IMPORT", false, "PGP"));
    verify(mapper, times(2)).upsertFileTemplateConfig(any());
  }

  @Test
  @DisplayName("已配置显式预处理流水线的导入模板允许更新")
  void shouldAllowUpdate_whenExplicitPipelineExists() {
    when(mapper.selectById("t1", 1L)).thenReturn(existing(true, "PGP"));
    when(mapper.hasPreprocessPipeline("t1", "test", 1)).thenReturn(true);
    FileTemplateUpdateRequest request = new FileTemplateUpdateRequest();
    request.setTenantId("t1");
    request.setDescription("changed");
    service.update(1L, request);
    verify(mapper).upsertFileTemplateConfig(any());
  }

  @Test
  @DisplayName("没有显式流水线时更新为 PGP 被拒绝")
  void shouldRejectUpdate_whenSwitchingToPgpWithoutPipeline() {
    when(mapper.selectById("t1", 1L)).thenReturn(existing(true, "NONE"));
    FileTemplateUpdateRequest request = new FileTemplateUpdateRequest();
    request.setTenantId("t1");
    request.setEncryptType("PGP");
    assertThatThrownBy(() -> service.update(1L, request)).isInstanceOf(BizException.class);
    verify(mapper, never()).upsertFileTemplateConfig(any());
  }

  @Test
  @DisplayName("启用没有显式流水线的 PGP 导入模板被拒绝")
  void shouldRejectEnable_whenPgpWithoutPipeline() {
    when(mapper.selectById("t1", 1L)).thenReturn(existing(false, "PGP"));
    assertThatThrownBy(() -> service.toggle(1L, "t1", true)).isInstanceOf(BizException.class);
    verify(mapper, never()).toggleEnabledIfUnchanged(any());
  }

  private static FileTemplateCreateRequest createRequest(
      String templateType, boolean enabled, String encryptType) {
    FileTemplateCreateRequest request = new FileTemplateCreateRequest();
    request.setTenantId("t1");
    request.setTemplateCode("test");
    request.setTemplateType(templateType);
    request.setFileFormatType("DELIMITED");
    request.setEnabled(enabled);
    request.setEncryptType(encryptType);
    return request;
  }

  private static FileTemplateConfigQueryView existing(boolean enabled, String encryptType) {
    return FileTemplateConfigQueryView.builder()
        .id(1L)
        .templateCode("test")
        .version(1)
        .templateType("IMPORT")
        .fileFormatType("DELIMITED")
        .encryptType(encryptType)
        .enabled(enabled)
        .build();
  }
}
