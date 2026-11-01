package io.github.pinpols.batch.console.domain.audit.application.ai;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.config.ConsoleAiClients;
import io.github.pinpols.batch.console.config.ConsoleAiProperties;
import io.github.pinpols.batch.console.domain.audit.service.ConsoleAiAuthorizationService;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** AI 图片能力与上传入口策略。 */
@Service
@RequiredArgsConstructor
public class ConsoleAiImageApplicationService {
  private final ConsoleAiAttachmentUseCase attachments;
  private final ConsoleAiProperties properties;
  private final ObjectProvider<ConsoleAiClients> clientsProvider;
  private final ConsoleRequestMetadataResolver metadataResolver;
  private final ConsoleAiAuthorizationService authorizationService;

  public record ConsoleAiCapabilities(
      boolean imageInput, int maxImages, int maxImageBytes, long maxTotalBytes) {}

  public ConsoleAiCapabilities capabilities() {
    authorizationService.assertAllowed();
    boolean imageInput = attachments.available() && supportsImageInput();
    return new ConsoleAiCapabilities(
        imageInput,
        imageInput ? properties.getImage().getMaxImages() : 0,
        imageInput ? properties.getImage().getMaxFileBytes() : 0,
        imageInput ? properties.getImage().getMaxTotalBytes() : 0);
  }

  public ConsoleAiAttachmentUseCase.AttachmentView upload(
      UUID clientAttachmentId, MultipartFile file) throws IOException {
    authorizationService.assertAllowed();
    if (!supportsImageInput()) {
      throw BizException.of(ResultCode.FORBIDDEN, "error.ai.assistant_not_configured");
    }
    if (file.getSize() < 1 || file.getSize() > properties.getImage().getMaxFileBytes()) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.ai.image_invalid");
    }
    ConsoleRequestMetadata metadata = currentIdentity();
    return attachments.upload(
        metadata.tenantId(), metadata.operatorId(), clientAttachmentId, file.getBytes());
  }

  public ConsoleAiAttachmentUseCase.AttachmentView status(UUID clientAttachmentId) {
    authorizationService.assertAllowed();
    ConsoleRequestMetadata metadata = currentIdentity();
    return attachments.status(metadata.tenantId(), metadata.operatorId(), clientAttachmentId);
  }

  public ConsoleAiAttachmentUseCase.ImageContent content(UUID id) {
    authorizationService.assertAllowed();
    ConsoleRequestMetadata metadata = currentIdentity();
    return attachments.content(metadata.tenantId(), metadata.operatorId(), id);
  }

  public void deleteDraft(UUID id) {
    authorizationService.assertAllowed();
    ConsoleRequestMetadata metadata = currentIdentity();
    attachments.deleteDraft(metadata.tenantId(), metadata.operatorId(), id);
  }

  private ConsoleRequestMetadata currentIdentity() {
    ConsoleRequestMetadata metadata = metadataResolver.current();
    if (EmptyChecks.isNull(metadata)
        || !Texts.hasText(metadata.tenantId())
        || !Texts.hasText(metadata.operatorId())) {
      throw BizException.of(ResultCode.FORBIDDEN, ResultCode.FORBIDDEN.detailKey());
    }
    return metadata;
  }

  private boolean supportsImageInput() {
    ConsoleAiClients clients = clientsProvider.getIfAvailable();
    return EmptyChecks.isNotNull(clients)
        && EmptyChecks.isNotNull(clients.primary())
        && clients.primary().imageInput();
  }
}
