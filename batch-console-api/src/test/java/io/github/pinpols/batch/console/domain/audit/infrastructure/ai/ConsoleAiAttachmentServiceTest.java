package io.github.pinpols.batch.console.domain.audit.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.service.BatchObjectCryptoService;
import io.github.pinpols.batch.common.storage.BatchObjectStore;
import io.github.pinpols.batch.console.config.ConsoleAiProperties;
import io.github.pinpols.batch.console.domain.audit.application.ai.ConsoleAiAttachmentBindingInput;
import io.github.pinpols.batch.console.domain.audit.entity.ConsoleAiAttachmentEntity;
import io.github.pinpols.batch.console.domain.audit.mapper.ConsoleAiAttachmentMapper;
import io.github.pinpols.batch.console.domain.audit.param.ConsoleAiAttachmentBindParam;
import io.github.pinpols.batch.console.support.ratelimit.SlidingWindowRateLimiter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("AI 附件服务:图片上传开关、草稿配额与对象加密约束")
class ConsoleAiAttachmentServiceTest {
  @Mock
  private ConsoleAiAttachmentMapper mapper;

  @Mock
  private BatchObjectCryptoService crypto;

  @Mock
  private BatchObjectStore store;

  @Mock
  private PlatformTransactionManager transactionManager;

  @Mock
  private SlidingWindowRateLimiter rateLimiter;

  private final ConsoleAiProperties properties = new ConsoleAiProperties();
  private ConsoleAiAttachmentService service;

  @BeforeEach
  void setUp() {
    properties.setImageInputEnabled(true);
    properties.getPersistence().setEnabled(true);
    service = new ConsoleAiAttachmentService(
        mapper, properties, crypto, store, transactionManager, rateLimiter);
  }

  @Test
  @DisplayName("无附件时绑定保持空操作，不触发开关或数据库检查")
  void shouldLeaveBindingEmpty_whenAttachmentIdsAreNullOrEmpty() {
    properties.setImageInputEnabled(false);
    ConsoleAiAttachmentBindingInput nullIds =
        ConsoleAiAttachmentBindingInput.builder().build();
    ConsoleAiAttachmentBindingInput emptyIds =
        ConsoleAiAttachmentBindingInput.builder().ids(List.of()).build();

    service.bind(nullIds);
    service.bind(emptyIds);

    Mockito.verifyNoInteractions(mapper);
  }

  @Test
  @DisplayName("有效草稿按原租户、所有者、会话及轮次绑定")
  void shouldKeepDraftBindingIdentityAndExpiration_whenBindingValidDraft() {
    UUID attachmentId = UUID.randomUUID();
    Instant expiration = Instant.now().plusSeconds(3600);
    ConsoleAiAttachmentEntity draft = new ConsoleAiAttachmentEntity();
    draft.setId(attachmentId);
    draft.setOwnerUserId("owner-a");
    draft.setStatus("DRAFT");
    draft.setExpiresAt(expiration);
    draft.setByteSize(10L);
    when(mapper.byId("tenant-a", attachmentId)).thenReturn(draft);
    when(mapper.bind(any(ConsoleAiAttachmentBindParam.class))).thenReturn(1);
    ConsoleAiAttachmentBindingInput binding = ConsoleAiAttachmentBindingInput.builder()
        .tenantId("tenant-a")
        .ownerUserId("owner-a")
        .ids(List.of(attachmentId))
        .conversationId("conversation-a")
        .turnNo(7L)
        .expiresAt(expiration)
        .build();

    service.bind(binding);

    ArgumentCaptor<ConsoleAiAttachmentBindParam> captor =
        ArgumentCaptor.forClass(ConsoleAiAttachmentBindParam.class);
    verify(mapper).bind(captor.capture());
    ConsoleAiAttachmentBindParam parameter = captor.getValue();
    assertThat(parameter.tenantId()).isEqualTo("tenant-a");
    assertThat(parameter.id()).isEqualTo(attachmentId);
    assertThat(parameter.ownerUserId()).isEqualTo("owner-a");
    assertThat(parameter.conversationId()).isEqualTo("conversation-a");
    assertThat(parameter.turnNo()).isEqualTo(7L);
    assertThat(parameter.expiresAt()).isEqualTo(expiration);
  }

  @Test
  @DisplayName("图片上传开关关闭:拒绝上传且不写入对象存储")
  void shouldRejectUpload_whenImageSwitchOff() throws Exception {
    properties.setImageInputEnabled(false);

    assertThatThrownBy(() -> service.upload("tenant", "owner", UUID.randomUUID(), png()))
        .isInstanceOf(BizException.class)
        .satisfies(error ->
            assertThat(((BizException) error).getCode()).isEqualTo(ResultCode.SERVICE_UNAVAILABLE));
    verify(store, never())
        .put(anyString(), anyString(), any(InputStream.class), anyLong(), anyString());
  }

  @Test
  @DisplayName("同一客户端附件标识内容变更:按冲突拒绝且不写入对象存储")
  void shouldRejectUpload_whenContentDiffersFromExistingId() throws Exception {
    when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    when(rateLimiter.tryAcquire(anyString(), eq(12))).thenReturn(true);
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
  @DisplayName("草稿配额已满:按限流拒绝且不插入记录,也不写入对象存储")
  void shouldRejectUpload_whenDraftQuotaFull() throws Exception {
    when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    when(rateLimiter.tryAcquire(anyString(), eq(12))).thenReturn(true);
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
  @DisplayName("已绑定归属的图片:对象未加密时拒绝读取,且不触发解密")
  void shouldRejectContent_whenObjectNotEncrypted() {
    when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
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
