package io.github.pinpols.batch.console.application.idempotency;

import io.github.pinpols.batch.console.mapper.ConsoleIdempotencyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Console HTTP 幂等完成态的数据库兜底。
 *
 * <p>Redis 负责低延迟并发占位；数据库只保存成功完成的短键哈希，用来覆盖 Redis 在请求完成时短暂不可用的窗口。
 */
@Service
@RequiredArgsConstructor
public class ConsoleDurableIdempotencyStore {

  private final ConsoleIdempotencyMapper mapper;

  public boolean isCompleted(String tenantId, String keyDigest) {
    return mapper.countCompleted(tenantId, keyDigest) > 0;
  }

  public void markCompleted(String tenantId, String keyDigest) {
    mapper.insertCompleted(tenantId, keyDigest);
  }
}
