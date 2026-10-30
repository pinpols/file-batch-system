package io.github.pinpols.batch.console.domain.file.infrastructure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
@DisplayName("定长文件模板的配置写入校验")
class FixedWidthTemplateValidationTest {
  private static final String INVALID = "[{\"target\":\"name\",\"start\":1.5,\"length\":2}]";

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

  @Test
  @DisplayName("创建启用模板时在写入前拒绝小数映射")
  void shouldRejectFractionalMapping_whenCreatingEnabledTemplate() {
    FileTemplateCreateRequest request = new FileTemplateCreateRequest();
    request.setTenantId("t1");
    request.setTemplateCode("test");
    request.setTemplateType("IMPORT");
    request.setFileFormatType("FIXED_WIDTH");
    request.setEnabled(true);
    request.setFieldMappingsJson(INVALID);
    assertThatThrownBy(() -> service.create(request)).isInstanceOf(BizException.class);
    verify(mapper, never()).upsertFileTemplateConfig(any());
  }

  @Test
  @DisplayName("更新启用模板时在写入前拒绝小数映射")
  void shouldRejectFractionalMapping_whenUpdatingEnabledTemplate() {
    when(mapper.selectById("t1", 1L)).thenReturn(existing(true, null));
    FileTemplateUpdateRequest request = new FileTemplateUpdateRequest();
    request.setTenantId("t1");
    request.setFieldMappingsJson(INVALID);
    assertThatThrownBy(() -> service.update(1L, request)).isInstanceOf(BizException.class);
    verify(mapper, never()).upsertFileTemplateConfig(any());
  }

  @Test
  @DisplayName("启用模板时在写入前拒绝小数映射")
  void shouldRejectFractionalMapping_whenEnablingTemplate() {
    when(mapper.selectById("t1", 1L)).thenReturn(existing(false, INVALID));
    assertThatThrownBy(() -> service.toggle(1L, "t1", true)).isInstanceOf(BizException.class);
    verify(mapper, never()).toggleEnabled("t1", 1L, true);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "[]",
        "[{}]",
        "[{\"target\":\" \u0020\",\"start\":0,\"length\":1}]",
        "[{\"target\":\"x\",\"start\":2147483648,\"length\":1}]",
        "[{\"target\":\"x\",\"start\":-1,\"length\":1}]",
        "[{\"target\":\"x\",\"start\":0,\"length\":0}]",
        "[{\"target\":\"x\",\"start\":0,\"length\":1.5}]",
        "[{\"target\":\"x\",\"start\":0,\"length\":1},{}]"
      })
  @DisplayName("创建启用模板时拒绝非空无效映射")
  void shouldRejectInvalidNonemptyMapping_whenCreatingEnabledTemplate(String mapping) {
    FileTemplateCreateRequest request = createRequest(true, mapping);
    assertThatThrownBy(() -> service.create(request)).isInstanceOf(BizException.class);
    verify(mapper, never()).upsertFileTemplateConfig(any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "{}", "[{\"target\":\"x\",\"start\":0,\"length\":2}]"})
  @DisplayName("创建启用模板时允许空骨架和有效映射")
  void shouldAllowSkeletonAndValidMapping_whenCreatingEnabledTemplate(String mapping) {
    service.create(createRequest(true, mapping.isEmpty() ? null : mapping));
    verify(mapper).upsertFileTemplateConfig(any());
  }

  @Test
  @DisplayName("未启用草稿允许不完整映射")
  void shouldAllowIncompleteMapping_whenTemplateIsDisabled() {
    service.create(createRequest(false, INVALID));
    verify(mapper).upsertFileTemplateConfig(any());
  }

  @Test
  @DisplayName("启用的定长空骨架不受 JSON 内部空格影响")
  void shouldAllowWhitespace_whenEmptySkeletonIsEnabled() {
    service.create(createRequest(true, "{   }"));
    verify(mapper).upsertFileTemplateConfig(any());
  }

  private static FileTemplateCreateRequest createRequest(boolean enabled, String mapping) {
    FileTemplateCreateRequest request = new FileTemplateCreateRequest();
    request.setTenantId("t1");
    request.setTemplateCode("test");
    request.setTemplateType("IMPORT");
    request.setFileFormatType("FIXED_WIDTH");
    request.setEnabled(enabled);
    request.setFieldMappingsJson(mapping);
    return request;
  }

  private static FileTemplateConfigQueryView existing(boolean enabled, Object mapping) {
    return FileTemplateConfigQueryView.builder()
        .templateCode("test")
        .version(1)
        .templateType("IMPORT")
        .fileFormatType("FIXED_WIDTH")
        .enabled(enabled)
        .fieldMappings(mapping)
        .build();
  }
}
