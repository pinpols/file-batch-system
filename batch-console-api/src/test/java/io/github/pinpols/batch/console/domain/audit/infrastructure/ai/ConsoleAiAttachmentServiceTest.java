package io.github.pinpols.batch.console.domain.audit.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.service.BatchObjectCryptoService;
import io.github.pinpols.batch.common.storage.BatchObjectStore;
import io.github.pinpols.batch.console.config.ConsoleAiProperties;
import io.github.pinpols.batch.console.domain.audit.entity.ConsoleAiAttachmentEntity;
import io.github.pinpols.batch.console.domain.audit.mapper.ConsoleAiAttachmentMapper;
import io.github.pinpols.batch.console.support.ratelimit.SlidingWindowRateLimiter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class ConsoleAiAttachmentServiceTest {
  private final ConsoleAiAttachmentMapper mapper = mock(ConsoleAiAttachmentMapper.class);
  private final BatchObjectCryptoService crypto = mock(BatchObjectCryptoService.class);
  private final BatchObjectStore store = mock(BatchObjectStore.class);
  private final PlatformTransactionManager transactionManager =
      mock(PlatformTransactionManager.class);
  private final SlidingWindowRateLimiter rateLimiter = mock(SlidingWindowRateLimiter.class);
  private final ConsoleAiProperties properties = new ConsoleAiProperties();
  private ConsoleAiAttachmentService service;

  @BeforeEach
  void setUp() {
    properties.setImageInputEnabled(true);
    properties.getPersistence().setEnabled(true);
    when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    when(rateLimiter.tryAcquire(anyString(), eq(12))).thenReturn(true);
    service = new ConsoleAiAttachmentService(
        mapper, properties, crypto, store, transactionManager, rateLimiter);
  }

  @Test
  void imageUploadRemainsDisabledWithoutExplicitFeatureSwitch() throws Exception {
    properties.setImageInputEnabled(false);

    assertThatThrownBy(() -> service.upload("tenant", "owner", UUID.randomUUID(), png()))
        .isInstanceOf(BizException.class)
        .satisfies(error ->
            assertThat(((BizException) error).getCode()).isEqualTo(ResultCode.SERVICE_UNAVAILABLE));
    verify(store, never())
        .put(anyString(), anyString(), any(InputStream.class), anyLong(), anyString());
  }

  @Test
  void rejectsChangedContentForAnExistingClientAttachmentId() throws Exception {
    UUID clientId = UUID.randomUUID();
    ConsoleAiAttachmentEntity existing = new ConsoleAiAttachmentEntity();
    existing.setInputSha256("0".repeat(64));
    existing.setStatus("DRAFT");
    when(mapper.byClientId("tenant", "owner", clientId)).thenReturn(existing);

    assertThatThrownBy(() -> service.upload("tenant", "owner", clientId, png()))
        .isInstanceOf(BizException.class)
        .satisfies(
            error -> assertThat(((BizException) error).getCode()).isEqualTo(ResultCode.CONFLICT));
    verify(store, never())
        .put(anyString(), anyString(), any(InputStream.class), anyLong(), anyString());
  }

  @Test
  void rejectsUploadWhenDraftQuotaIsFull() throws Exception {
    when(mapper.activeDraftCount("tenant", "owner")).thenReturn(16);

    assertThatThrownBy(() -> service.upload("tenant", "owner", UUID.randomUUID(), png()))
        .isInstanceOf(BizException.class)
        .satisfies(error ->
            assertThat(((BizException) error).getCode()).isEqualTo(ResultCode.RATE_LIMITED));
    verify(mapper, never()).insert(any());
    verify(store, never())
        .put(anyString(), anyString(), any(InputStream.class), anyLong(), anyString());
  }

  @Test
  void rejectsUnencryptedObjectsEvenForAnOwnedBoundImage() {
    UUID id = UUID.randomUUID();
    ConsoleAiAttachmentEntity row = new ConsoleAiAttachmentEntity();
    row.setId(id);
    row.setObjectKey("ai/images/private");
    row.setOwnerUserId("owner");
    row.setStatus("BOUND");
    row.setExpiresAt(Instant.now().plusSeconds(3600));
    when(mapper.byId("tenant", id)).thenReturn(row);
    properties.getAttachment().setStorageBucket("private-bucket");
    when(store.get("private-bucket", "ai/images/private"))
        .thenReturn(new ByteArrayInputStream(new byte[] {1, 2, 3}));

    assertThatThrownBy(() -> service.content("tenant", "owner", id))
        .isInstanceOf(BizException.class)
        .satisfies(error ->
            assertThat(((BizException) error).getCode()).isEqualTo(ResultCode.SERVICE_UNAVAILABLE));
    verify(crypto, never()).decrypt(any(byte[].class));
  }

  private static byte[] png() throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", output);
    return output.toByteArray();
  }
}
