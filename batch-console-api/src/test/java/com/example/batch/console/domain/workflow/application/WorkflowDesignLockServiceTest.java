package com.example.batch.console.domain.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import org.springframework.data.redis.core.script.RedisScript;

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
    // 准备
    when(valueOps.setIfAbsent(
            eq(EXPECTED_KEY), anyString(), eq(WorkflowDesignLockService.LOCK_TTL)))
        .thenReturn(true);

    // 执行
    LockHolder holder = service.acquire(TENANT, DEF_ID, USER_ALICE);

    // 断言
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
  @DisplayName("renew:持锁人续期(Lua 返回 1) → expiresAt 推到 now + 5min")
  void shouldRenew_whenOwner() {
    // renew 走 RENEW_SCRIPT(GET+校验+SET 原子),owner 命中返回 1
    when(redisTemplate.execute(
            org.mockito.ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any(), any(), any()))
        .thenReturn(1L);

    LockHolder holder = service.renew(TENANT, DEF_ID, USER_ALICE);

    assertThat(holder.lockedBy()).isEqualTo(USER_ALICE);
    assertThat(holder.expiresAt()).isEqualTo(fixedNow.plus(Duration.ofMinutes(5)));
  }

  @Test
  @DisplayName("release:持锁人释放(Lua 返回 1) → 不抛异常")
  void shouldRelease_whenOwner() {
    when(redisTemplate.execute(
            org.mockito.ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any()))
        .thenReturn(1L);

    service.release(TENANT, DEF_ID, USER_ALICE);
  }

  @Test
  @DisplayName("release:非持锁人(Lua 返回 -1) → FORBIDDEN")
  void shouldThrowForbidden_whenReleaseByNonOwner() {
    when(redisTemplate.execute(
            org.mockito.ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any()))
        .thenReturn(-1L);
    // FORBIDDEN 错误消息读当前持锁人(best-effort)
    when(valueOps.get(EXPECTED_KEY)).thenReturn(serialize(USER_BOB, fixedNow.plusSeconds(60)));

    assertThatThrownBy(() -> service.release(TENANT, DEF_ID, USER_ALICE))
        .isInstanceOfSatisfying(
            BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(ResultCode.FORBIDDEN));
  }

  @Test
  @DisplayName("renew:锁已过期(Lua 返回 0) → CONFLICT,让前端重新 acquire")
  void shouldThrowConflict_whenRenewExpired() {
    when(redisTemplate.execute(
            org.mockito.ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any(), any(), any()))
        .thenReturn(0L);

    assertThatThrownBy(() -> service.renew(TENANT, DEF_ID, USER_ALICE))
        .isInstanceOfSatisfying(
            BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(ResultCode.CONFLICT));
  }

  private String serialize(String user, Instant expiresAt) {
    try {
      return objectMapper.writeValueAsString(new LockHolder(user, expiresAt));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
