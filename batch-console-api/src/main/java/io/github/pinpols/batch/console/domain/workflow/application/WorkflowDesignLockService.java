package io.github.pinpols.batch.console.domain.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * Workflow DAG designer 单人编辑锁(BE Spike).
 *
 * <p>语义:5min TTL Redis SETNX 软锁,避免多人同时编辑同一份 workflow 定义导致后保存覆盖前保存。 与 ShedLock 区分:
 *
 * <ul>
 *   <li>ShedLock = 调度类任务互斥(scheduled job),进程级、运维不感知
 *   <li>本锁 = 用户级 UI 编辑锁,可显式申请 / 续期 / 释放,持锁人写入 value 供 409 时给前端展示
 * </ul>
 *
 * <p>Key 形态 {@code wf-design-lock:{tenantId}:{definitionId}};value 为 JSON {@code {"lockedBy",
 * "expiresAt"}}。
 *
 * <p>详见 docs/design/workflow-dag-designer.md。
 */
@Service
@Slf4j
public class WorkflowDesignLockService {

  /** 锁 TTL 5 分钟。 */
  public static final Duration LOCK_TTL = Duration.ofMinutes(5);

  private static final String KEY_PREFIX = "wf-design-lock:";

  /**
   * 原子释放:GET → 校验 lockedBy == 调用者 → DEL,全程在 Redis 单线程内执行,消除「GET 后 TTL 过期、他人重新获锁、本调用误删他人锁」 的竞态。返回
   * 0=无锁(幂等) / 1=已删 / -1=非持锁人。lockedBy 用 Redis 内置 cjson 解析。
   */
  private static final RedisScript<Long> RELEASE_SCRIPT =
      new DefaultRedisScript<>(
          "local v = redis.call('GET', KEYS[1])\n"
              + "if not v then return 0 end\n"
              + "if cjson.decode(v)['lockedBy'] == ARGV[1] then\n"
              + "  return redis.call('DEL', KEYS[1])\n"
              + "else\n"
              + "  return -1\n"
              + "end",
          Long.class);

  /** 原子续期:GET → 校验 lockedBy == 调用者 → SET 新 payload + TTL。返回 0=锁不存在(已过期) / 1=已续 / -1=非持锁人。 */
  private static final RedisScript<Long> RENEW_SCRIPT =
      new DefaultRedisScript<>(
          "local v = redis.call('GET', KEYS[1])\n"
              + "if not v then return 0 end\n"
              + "if cjson.decode(v)['lockedBy'] == ARGV[1] then\n"
              + "  redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])\n"
              + "  return 1\n"
              + "else\n"
              + "  return -1\n"
              + "end",
          Long.class);

  private static final long RESULT_NOT_OWNER = -1L;
  private static final long RESULT_ABSENT = 0L;

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private Clock clock;

  public WorkflowDesignLockService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
    this.clock = Clock.systemUTC();
  }

  /** 测试构造:允许注入 fixed Clock 验证 expiresAt。仅可见 package-private。 */
  static WorkflowDesignLockService withClock(
      StringRedisTemplate redisTemplate, ObjectMapper objectMapper, Clock clock) {
    WorkflowDesignLockService svc = new WorkflowDesignLockService(redisTemplate, objectMapper);
    svc.setClock(clock);
    return svc;
  }

  private void setClock(Clock clock) {
    this.clock = clock;
  }

  /** 申请锁:SETNX 成功 → 返回当前持锁信息;别人持锁 → 抛 CONFLICT 带 lockedBy。 */
  public LockHolder acquire(String tenantId, Long definitionId, String userId) {
    String key = buildKey(tenantId, definitionId);
    Instant expiresAt = Instant.now(clock).plus(LOCK_TTL);
    LockHolder holder = new LockHolder(userId, expiresAt);
    String payload = serialize(holder);
    Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, payload, LOCK_TTL);
    if (Boolean.TRUE.equals(ok)) {
      return holder;
    }
    // 已被别人持锁 → 把现持锁信息塞给 FE 用于提示
    LockHolder current = readCurrent(key);
    throw BizException.of(
        ResultCode.CONFLICT,
        "error.workflow_design_lock.held_by_other",
        current != null ? current.lockedBy() : "unknown");
  }

  /** 释放锁:必须持锁人调用;非持锁人调用 → FORBIDDEN(防误删别人锁)。GET+校验+DEL 由 Lua 原子完成。 */
  public void release(String tenantId, Long definitionId, String userId) {
    String key = buildKey(tenantId, definitionId);
    Long result = redisTemplate.execute(RELEASE_SCRIPT, List.of(key), userId);
    if (result != null && result == RESULT_NOT_OWNER) {
      throw BizException.of(
          ResultCode.FORBIDDEN, "error.workflow_design_lock.not_owner", currentOwnerOrUnknown(key));
    }
    // 0(无锁,幂等) / 1(已删) 均视为成功
  }

  /** 续期 5 分钟:必须持锁人调用;锁不存在(过期) → CONFLICT 提示重新申请。GET+校验+SET 由 Lua 原子完成。 */
  public LockHolder renew(String tenantId, Long definitionId, String userId) {
    String key = buildKey(tenantId, definitionId);
    Instant expiresAt = Instant.now(clock).plus(LOCK_TTL);
    LockHolder renewed = new LockHolder(userId, expiresAt);
    Long result =
        redisTemplate.execute(
            RENEW_SCRIPT,
            List.of(key),
            userId,
            serialize(renewed),
            String.valueOf(LOCK_TTL.toMillis()));
    long code = result == null ? RESULT_ABSENT : result;
    if (code == RESULT_ABSENT) {
      throw BizException.of(ResultCode.CONFLICT, "error.workflow_design_lock.expired");
    }
    if (code == RESULT_NOT_OWNER) {
      throw BizException.of(
          ResultCode.FORBIDDEN, "error.workflow_design_lock.not_owner", currentOwnerOrUnknown(key));
    }
    return renewed;
  }

  /** 仅用于 FORBIDDEN 错误消息展示当前持锁人(best-effort);为空返回 "unknown"。 */
  private String currentOwnerOrUnknown(String key) {
    LockHolder current = readCurrent(key);
    return current != null ? current.lockedBy() : "unknown";
  }

  /** 读当前锁状态(供 fullUpdate 校验持锁人是否当前 user)。null 表示无锁。 */
  public LockHolder currentHolder(String tenantId, Long definitionId) {
    return readCurrent(buildKey(tenantId, definitionId));
  }

  private LockHolder readCurrent(String key) {
    String raw = redisTemplate.opsForValue().get(key);
    if (raw == null) {
      return null;
    }
    try {
      return objectMapper.readValue(raw, LockHolder.class);
    } catch (JsonProcessingException ex) {
      SwallowedExceptionLogger.info(WorkflowDesignLockService.class, "lock-value-parse-failed", ex);
      // 损坏的 value 视同无锁,让调用方按申请流程继续
      return null;
    }
  }

  private String serialize(LockHolder holder) {
    try {
      return objectMapper.writeValueAsString(holder);
    } catch (JsonProcessingException ex) {
      // 内置 record 序列化不应失败;真出错走系统异常
      throw BizException.of(
          ResultCode.SYSTEM_ERROR, "error.workflow_design_lock.serialize_failed", ex.getMessage());
    }
  }

  private static String buildKey(String tenantId, Long definitionId) {
    return KEY_PREFIX + tenantId + ":" + definitionId;
  }

  /** 锁持有信息载体(JSON 序列化)。 */
  public record LockHolder(String lockedBy, Instant expiresAt) {}
}
