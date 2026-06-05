package com.example.batch.console.domain.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.console.domain.workflow.application.WorkflowDesignLockService.LockHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** 单测 WorkflowDesignLockService:acquire / release / renew / not-owner / expired 5 case。 */
@ExtendWith(MockitoExtension.class)
// opsForValue stubbing 在多 case 共享,SETNX vs SET 分支命中互斥,允许 LENIENT
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WorkflowDesignLockService BE Spike 编辑锁")
class WorkflowDesignLockServiceTest {

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private StringRedisTemplate redisTemplate;

  @Mock private ValueOperations<String, String> valueOps;

  private final ObjectMapper objectMapper =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private final Instant fixedNow = Instant.parse("2026-06-04T08:00:00Z");
  private final Clock fixedClock = Clock.fixed(fixedNow, ZoneId.of("UTC"));

  private WorkflowDesignLockService service;

  private static final String TENANT = "ta";
  private static final Long DEF_ID = 42L;
  private static final String USER_ALICE = "alice";
  private static final String USER_BOB = "bob";
  private static final String EXPECTED_KEY = "wf-design-lock:ta:42";

  @BeforeEach
  void setUp() {
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    service = WorkflowDesignLockService.withClock(redisTemplate, objectMapper, fixedClock);
  }

  @Test
  @DisplayName("acquire:SETNX 成功 → 返回 lockedBy + expiresAt = now + 5min")
  void shouldAcquire_whenNotHeld() {
    // arrange
    when(valueOps.setIfAbsent(
            eq(EXPECTED_KEY), anyString(), eq(WorkflowDesignLockService.LOCK_TTL)))
        .thenReturn(true);

    // act
    LockHolder holder = service.acquire(TENANT, DEF_ID, USER_ALICE);

    // assert
    assertThat(holder.lockedBy()).isEqualTo(USER_ALICE);
    assertThat(holder.expiresAt()).isEqualTo(fixedNow.plus(Duration.ofMinutes(5)));
  }

  @Test
  @DisplayName("acquire:别人持锁 → CONFLICT 含 lockedBy")
  void shouldThrowConflict_whenHeldByOther() {
    when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
    when(valueOps.get(EXPECTED_KEY)).thenReturn(serialize(USER_BOB, fixedNow.plusSeconds(30)));

    assertThatThrownBy(() -> service.acquire(TENANT, DEF_ID, USER_ALICE))
        .isInstanceOfSatisfying(
            BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(ResultCode.CONFLICT));
  }

  @Test
  @DisplayName("renew:持锁人续期 → expiresAt 推到 now + 5min")
  void shouldRenew_whenOwner() {
    when(valueOps.get(EXPECTED_KEY)).thenReturn(serialize(USER_ALICE, fixedNow.plusSeconds(60)));

    LockHolder holder = service.renew(TENANT, DEF_ID, USER_ALICE);

    assertThat(holder.lockedBy()).isEqualTo(USER_ALICE);
    assertThat(holder.expiresAt()).isEqualTo(fixedNow.plus(Duration.ofMinutes(5)));
    verify(valueOps).set(eq(EXPECTED_KEY), anyString(), eq(WorkflowDesignLockService.LOCK_TTL));
  }

  @Test
  @DisplayName("release:持锁人释放 → DELETE key")
  void shouldRelease_whenOwner() {
    when(valueOps.get(EXPECTED_KEY)).thenReturn(serialize(USER_ALICE, fixedNow.plusSeconds(60)));

    service.release(TENANT, DEF_ID, USER_ALICE);

    verify(redisTemplate).delete(EXPECTED_KEY);
  }

  @Test
  @DisplayName("release:非持锁人 → FORBIDDEN,不 DELETE")
  void shouldThrowForbidden_whenReleaseByNonOwner() {
    when(valueOps.get(EXPECTED_KEY)).thenReturn(serialize(USER_BOB, fixedNow.plusSeconds(60)));

    assertThatThrownBy(() -> service.release(TENANT, DEF_ID, USER_ALICE))
        .isInstanceOfSatisfying(
            BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(ResultCode.FORBIDDEN));
    verify(redisTemplate, never()).delete(anyString());
  }

  @Test
  @DisplayName("renew:锁已过期 → CONFLICT,让前端重新 acquire")
  void shouldThrowConflict_whenRenewExpired() {
    when(valueOps.get(EXPECTED_KEY)).thenReturn(null);

    assertThatThrownBy(() -> service.renew(TENANT, DEF_ID, USER_ALICE))
        .isInstanceOfSatisfying(
            BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(ResultCode.CONFLICT));
    verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
  }

  private String serialize(String user, Instant expiresAt) {
    try {
      return objectMapper.writeValueAsString(new LockHolder(user, expiresAt));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
