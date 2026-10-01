package io.github.pinpols.batch.console.domain.audit.infrastructure.ai;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.service.BatchObjectCryptoService;
import io.github.pinpols.batch.common.storage.BatchObjectStore;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.config.ConsoleAiProperties;
import io.github.pinpols.batch.console.domain.audit.application.ai.ConsoleAiAttachmentUseCase;
import io.github.pinpols.batch.console.domain.audit.application.ai.ConsoleAiAttachmentUseCase.AttachmentView;
import io.github.pinpols.batch.console.domain.audit.application.ai.ConsoleAiAttachmentUseCase.ImageContent;
import io.github.pinpols.batch.console.domain.audit.entity.ConsoleAiAttachmentEntity;
import io.github.pinpols.batch.console.domain.audit.mapper.ConsoleAiAttachmentMapper;
import io.github.pinpols.batch.console.support.ratelimit.SlidingWindowRateLimiter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 管理 AI 私有图片存储、所有者校验和持久化对象删除意图。 */
@Service
@RequiredArgsConstructor
public class ConsoleAiAttachmentService implements ConsoleAiAttachmentUseCase {
  private final ConsoleAiAttachmentMapper mapper;
  private final ConsoleAiProperties aiProperties;
  private final BatchObjectCryptoService cryptoService;
  private final BatchObjectStore objectStore;
  private final PlatformTransactionManager transactionManager;
  private final SlidingWindowRateLimiter rateLimiter;

  @Override
  public AttachmentView upload(
      String tenantId, String ownerUserId, UUID clientAttachmentId, byte[] input) {
    requireEnabled();
    enforceUploadRate(tenantId, ownerUserId);
    ConsoleAiImageNormalizer.NormalizedImage image =
        ConsoleAiImageNormalizer.normalize(input, aiProperties.getImage());
    String inputSha256 = sha256(input);
    UploadReservation reservation = template().execute(status -> {
      mapper.setTenantContext(tenantId);
      mapper.lockUploadQuota(tenantId);
      ConsoleAiAttachmentEntity existing =
          mapper.byClientId(tenantId, ownerUserId, clientAttachmentId);
      if (EmptyChecks.isNotNull(existing)) return new UploadReservation(existing, false);
      ConsoleAiProperties.Image limits = aiProperties.getImage();
      if (mapper.activeDraftCount(tenantId, ownerUserId) >= limits.getMaxDraftsPerUser()
          || mapper.activeDraftBytes(tenantId, ownerUserId) + image.bytes().length
              > limits.getMaxDraftBytesPerUser()
          || mapper.retainedBytesForUser(tenantId, ownerUserId) + image.bytes().length
              > limits.getMaxRetainedBytesPerUser()
          || mapper.retainedBytesForTenant(tenantId) + image.bytes().length
              > limits.getMaxRetainedBytesPerTenant()) {
        throw BizException.of(ResultCode.RATE_LIMITED, "error.ai.rate_limited");
      }
      ConsoleAiAttachmentEntity created = new ConsoleAiAttachmentEntity();
      created.setTenantId(tenantId);
      created.setId(UUID.randomUUID());
      created.setOwnerUserId(ownerUserId);
      created.setClientAttachmentId(clientAttachmentId);
      created.setInputSha256(inputSha256);
      created.setObjectKey("ai/images/" + UUID.randomUUID());
      created.setByteSize((long) image.bytes().length);
      created.setExpiresAt(
          Instant.now().plus(aiProperties.getImage().getDraftRetentionHours(), ChronoUnit.HOURS));
      boolean inserted = mapper.insert(created) == 1;
      return new UploadReservation(
          mapper.byClientId(tenantId, ownerUserId, clientAttachmentId), inserted);
    });
    ConsoleAiAttachmentEntity row = EmptyChecks.isNull(reservation) ? null : reservation.row();
    if (EmptyChecks.isNull(row)) throw unavailable();
    if (!inputSha256.equals(row.getInputSha256())) {
      throw BizException.of(ResultCode.CONFLICT, "error.common.state_conflict");
    }
    if ("DRAFT".equals(row.getStatus()) || "BOUND".equals(row.getStatus())) return view(row);
    if (!reservation.created()) {
      throw BizException.of(ResultCode.CONFLICT, "error.common.state_conflict");
    }
    byte[] ciphertext = cryptoService.encrypt(image.bytes(), null);
    objectStore.put(
        attachmentBucket(),
        row.getObjectKey(),
        new ByteArrayInputStream(ciphertext),
        ciphertext.length,
        "application/octet-stream");
    int updated = template().execute(status -> {
      mapper.setTenantContext(tenantId);
      return mapper.markDraft(
          tenantId,
          row.getId(),
          image.mediaType(),
          image.bytes().length,
          image.width(),
          image.height());
    });
    if (updated != 1) throw unavailable();
    return view(template().execute(status -> {
      mapper.setTenantContext(tenantId);
      return mapper.byId(tenantId, row.getId());
    }));
  }

  @Override
  public AttachmentView status(String tenantId, String ownerUserId, UUID clientAttachmentId) {
    requireEnabled();
    return template().execute(status -> {
      mapper.setTenantContext(tenantId);
      ConsoleAiAttachmentEntity row = mapper.byClientId(tenantId, ownerUserId, clientAttachmentId);
      if (EmptyChecks.isNull(row) || row.getExpiresAt().isBefore(Instant.now())) throw notFound();
      return view(row);
    });
  }

  @Override
  public ImageContent content(String tenantId, String ownerUserId, UUID id) {
    ConsoleAiAttachmentEntity row = owned(tenantId, ownerUserId, id);
    if (!"BOUND".equals(row.getStatus())) throw notFound();
    try (InputStream stream = objectStore.get(attachmentBucket(), row.getObjectKey())) {
      int limit = aiProperties.getImage().getMaxFileBytes() + 1024;
      byte[] encrypted = stream.readNBytes(limit + 1);
      if (encrypted.length > limit || !cryptoService.isEncryptedContent(encrypted)) {
        throw unavailable();
      }
      byte[] decrypted = cryptoService.decrypt(encrypted);
      if (decrypted.length != row.getByteSize()) throw unavailable();
      return new ImageContent(decrypted, row.getMediaType());
    } catch (IOException exception) {
      throw unavailable();
    }
  }

  @Override
  public void deleteDraft(String tenantId, String ownerUserId, UUID id) {
    template().executeWithoutResult(status -> {
      mapper.setTenantContext(tenantId);
      ConsoleAiAttachmentEntity row = mapper.byId(tenantId, id);
      if (EmptyChecks.isNull(row)
          || !ownerUserId.equals(row.getOwnerUserId())
          || "BOUND".equals(row.getStatus())) throw notFound();
      mapper.enqueueOne(tenantId, id);
      mapper.deleteOne(tenantId, id);
    });
  }

  public List<ConsoleAiAttachmentEntity> validateDrafts(
      String tenantId, String ownerUserId, List<UUID> ids) {
    requireEnabled();
    if (EmptyChecks.isEmpty(ids)
        || ids.size() > aiProperties.getImage().getMaxImages()
        || ids.stream().distinct().count() != ids.size()) {
      throw invalid();
    }
    mapper.setTenantContext(tenantId);
    List<ConsoleAiAttachmentEntity> rows = ids.stream()
        .map(id -> {
          ConsoleAiAttachmentEntity row = mapper.byId(tenantId, id);
          if (EmptyChecks.isNull(row)
              || !ownerUserId.equals(row.getOwnerUserId())
              || !"DRAFT".equals(row.getStatus())
              || !row.getExpiresAt().isAfter(Instant.now())) {
            throw notFound();
          }
          return row;
        })
        .toList();
    if (rows.stream().mapToLong(ConsoleAiAttachmentEntity::getByteSize).sum()
        > aiProperties.getImage().getMaxTotalBytes()) {
      throw invalid();
    }
    return rows;
  }

  public void bind(
      String tenantId,
      String ownerUserId,
      List<UUID> ids,
      String conversationId,
      long turnNo,
      Instant expiresAt) {
    if (EmptyChecks.isEmpty(ids)) return;
    validateDrafts(tenantId, ownerUserId, ids);
    for (UUID id : ids) {
      if (mapper.bind(tenantId, id, ownerUserId, conversationId, turnNo, expiresAt) != 1) {
        throw BizException.of(ResultCode.CONFLICT, "error.common.state_conflict");
      }
    }
  }

  public List<AttachmentView> byTurn(String tenantId, String conversationId, long turnNo) {
    return template().execute(status -> {
      mapper.setTenantContext(tenantId);
      return mapper.byTurn(tenantId, conversationId, turnNo).stream()
          .map(ConsoleAiAttachmentService::view)
          .toList();
    });
  }

  public List<ImageContent> contentsForTurn(
      String tenantId, String ownerUserId, String conversationId, long turnNo) {
    return byTurn(tenantId, conversationId, turnNo).stream()
        .map(attachment -> content(tenantId, ownerUserId, attachment.id()))
        .toList();
  }

  public void enqueueConversation(String tenantId, String conversationId) {
    mapper.setTenantContext(tenantId);
    mapper.enqueueForConversation(tenantId, conversationId);
  }

  public void enqueueExpiredConversations(String tenantId, Instant now) {
    mapper.setTenantContext(tenantId);
    mapper.enqueueExpiredConversations(tenantId, now);
  }

  public void cleanTenant(String tenantId) {
    template().executeWithoutResult(status -> {
      mapper.setTenantContext(tenantId);
      Instant now = Instant.now();
      mapper.enqueueExpiredDrafts(tenantId, now);
      mapper.deleteExpiredDrafts(tenantId, now);
    });
    List<String> keys = template().execute(status -> {
      mapper.setTenantContext(tenantId);
      return mapper.cleanupKeys(tenantId, 100);
    });
    if (EmptyChecks.isNull(keys)) return;
    for (String key : keys) {
      try {
        objectStore.delete(attachmentBucket(), key);
        template().executeWithoutResult(status -> {
          mapper.setTenantContext(tenantId);
          mapper.deleteCleanupKey(tenantId, key);
        });
      } catch (RuntimeException exception) {
        template().executeWithoutResult(status -> {
          mapper.setTenantContext(tenantId);
          mapper.incrementCleanupAttempts(tenantId, key);
        });
      }
    }
  }

  private String attachmentBucket() {
    return aiProperties.getAttachment().getStorageBucket();
  }

  private ConsoleAiAttachmentEntity owned(String tenantId, String ownerUserId, UUID id) {
    ConsoleAiAttachmentEntity row = template().execute(status -> {
      mapper.setTenantContext(tenantId);
      return mapper.byId(tenantId, id);
    });
    if (EmptyChecks.isNull(row)
        || !ownerUserId.equals(row.getOwnerUserId())
        || !row.getExpiresAt().isAfter(Instant.now())) throw notFound();
    return row;
  }

  private void requireEnabled() {
    if (!available()) throw unavailable();
  }

  private void enforceUploadRate(String tenantId, String ownerUserId) {
    try {
      if (!rateLimiter.tryAcquire(
          "ai:image-upload:tenant:" + tenantId + ":user:" + ownerUserId,
          aiProperties.getImage().getUploadLimitPerMinute())) {
        throw BizException.of(ResultCode.RATE_LIMITED, "error.ai.rate_limited");
      }
    } catch (DataAccessException exception) {
      throw unavailable();
    }
  }

  @Override
  public boolean available() {
    return aiProperties.isImageInputEnabled()
        && aiProperties.getPersistence().isEnabled()
        && !cryptoService.isBypassMode();
  }

  private TransactionTemplate template() {
    return new TransactionTemplate(transactionManager);
  }

  private static AttachmentView view(ConsoleAiAttachmentEntity row) {
    return new AttachmentView(
        row.getId(),
        row.getClientAttachmentId(),
        row.getStatus(),
        row.getMediaType(),
        row.getByteSize(),
        row.getWidth(),
        row.getHeight(),
        row.getExpiresAt());
  }

  private static String sha256(byte[] input) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  private static BizException invalid() {
    return BizException.of(ResultCode.INVALID_ARGUMENT, "error.ai.image_invalid");
  }

  private static BizException notFound() {
    return BizException.of(ResultCode.NOT_FOUND, "error.ai.image_not_found");
  }

  private static BizException unavailable() {
    return BizException.of(ResultCode.SERVICE_UNAVAILABLE, "error.ai.image_unavailable");
  }

  private record UploadReservation(ConsoleAiAttachmentEntity row, boolean created) {}
}
