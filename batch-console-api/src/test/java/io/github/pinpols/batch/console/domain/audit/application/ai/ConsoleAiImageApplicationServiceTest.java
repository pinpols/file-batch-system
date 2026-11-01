package io.github.pinpols.batch.console.domain.audit.application.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.config.ConsoleAiClients;
import io.github.pinpols.batch.console.config.ConsoleAiProperties;
import io.github.pinpols.batch.console.domain.audit.service.ConsoleAiAuthorizationService;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockMultipartFile;

@DisplayName("AI 图片应用入口：能力与上传限制")
class ConsoleAiImageApplicationServiceTest {
  @Test
  @DisplayName("未授权用户不能通过应用入口获取图片能力或上传")
  void shouldEnforceAuthorizationAtApplicationEntry() {
    ConsoleAiAttachmentUseCase attachments = mock(ConsoleAiAttachmentUseCase.class);
    ConsoleAiAuthorizationService authorization = mock(ConsoleAiAuthorizationService.class);
    doThrow(BizException.of(ResultCode.FORBIDDEN, ResultCode.FORBIDDEN.detailKey()))
        .when(authorization).assertAllowed();
    StaticListableBeanFactory factory = new StaticListableBeanFactory();
    ConsoleAiImageApplicationService service = new ConsoleAiImageApplicationService(
        attachments, new ConsoleAiProperties(), factory.getBeanProvider(ConsoleAiClients.class),
        mock(ConsoleRequestMetadataResolver.class), authorization);
    assertThatThrownBy(service::capabilities).isInstanceOf(BizException.class);
    MockMultipartFile file = new MockMultipartFile("file", new byte[] {1});
    UUID clientId = UUID.randomUUID();
    assertThatThrownBy(() -> service.upload(clientId, file)).isInstanceOf(BizException.class);
    assertThatThrownBy(() -> service.status(clientId)).isInstanceOf(BizException.class);
    assertThatThrownBy(() -> service.content(clientId)).isInstanceOf(BizException.class);
    assertThatThrownBy(() -> service.deleteDraft(clientId)).isInstanceOf(BizException.class);
    verify(attachments, never()).upload(anyString(), anyString(), any(), any());
  }

  @Test
  @DisplayName("主模型不支持图片时能力关闭且上传不触达附件存储")
  void shouldRejectUploadWhenPrimaryModelDoesNotSupportImages() {
    ConsoleAiAttachmentUseCase attachments = mock(ConsoleAiAttachmentUseCase.class);
    ConsoleAiProperties properties = new ConsoleAiProperties();
    StaticListableBeanFactory factory = new StaticListableBeanFactory();
    ConsoleAiImageApplicationService service = new ConsoleAiImageApplicationService(
        attachments, properties, factory.getBeanProvider(ConsoleAiClients.class),
        mock(ConsoleRequestMetadataResolver.class), mock(ConsoleAiAuthorizationService.class));
    assertThat(service.capabilities().imageInput()).isFalse();
    MockMultipartFile file = new MockMultipartFile("file", new byte[] {1});
    UUID clientId = UUID.randomUUID();
    assertThatThrownBy(() -> service.upload(clientId, file))
        .isInstanceOf(BizException.class)
        .satisfies(exception -> assertThat(((BizException) exception).getCode())
            .isEqualTo(ResultCode.FORBIDDEN));
    verify(attachments, never()).upload(anyString(), anyString(), any(), any());
  }

  @Test
  @DisplayName("支持图片但文件超限时拒绝上传且不触达附件存储")
  void shouldRejectOversizedImage() {
    ConsoleAiAttachmentUseCase attachments = mock(ConsoleAiAttachmentUseCase.class);
    ConsoleAiProperties properties = new ConsoleAiProperties();
    properties.getImage().setMaxFileBytes(1);
    StaticListableBeanFactory factory = new StaticListableBeanFactory();
    factory.addBean("clients", new ConsoleAiClients(
        new ConsoleAiClients.ProviderClient("test", null, true), null));
    when(attachments.available()).thenReturn(true);
    ConsoleAiImageApplicationService service = new ConsoleAiImageApplicationService(
        attachments, properties, factory.getBeanProvider(ConsoleAiClients.class),
        mock(ConsoleRequestMetadataResolver.class), mock(ConsoleAiAuthorizationService.class));
    assertThat(service.capabilities().maxImageBytes()).isEqualTo(1);
    MockMultipartFile file = new MockMultipartFile("file", new byte[] {1, 2});
    UUID clientId = UUID.randomUUID();
    assertThatThrownBy(() -> service.upload(clientId, file))
        .isInstanceOf(BizException.class)
        .satisfies(exception -> assertThat(((BizException) exception).getCode())
            .isEqualTo(ResultCode.INVALID_ARGUMENT));
    verify(attachments, never()).upload(anyString(), anyString(), any(), any());
  }

  @Test
  @DisplayName("图片可用且文件合法时按当前租户和用户交给附件存储")
  void shouldForwardValidImageWithRequestIdentity() throws Exception {
    ConsoleAiAttachmentUseCase attachments = mock(ConsoleAiAttachmentUseCase.class);
    ConsoleRequestMetadataResolver resolver = mock(ConsoleRequestMetadataResolver.class);
    when(resolver.current()).thenReturn(
        new ConsoleRequestMetadata("request", "trace", "tenant", "user", "key", "127.0.0.1"));
    StaticListableBeanFactory factory = new StaticListableBeanFactory();
    factory.addBean("clients", new ConsoleAiClients(
        new ConsoleAiClients.ProviderClient("test", null, true), null));
    ConsoleAiImageApplicationService service = new ConsoleAiImageApplicationService(
        attachments, new ConsoleAiProperties(), factory.getBeanProvider(ConsoleAiClients.class), resolver,
        mock(ConsoleAiAuthorizationService.class));
    UUID clientId = UUID.randomUUID();
    MockMultipartFile file = new MockMultipartFile("file", new byte[] {1, 2});
    service.upload(clientId, file);
    verify(attachments).upload("tenant", "user", clientId, new byte[] {1, 2});
  }
}
