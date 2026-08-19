package io.github.pinpols.batch.worker.core.support;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * 负责统一判断消费者异常是否可通过 Kafka 重投恢复。
 *
 * <p>控制面 5xx、连接失败、读超时和 DNS 失败属于 transient，必须保留 offset 让消息重投；业务 4xx、解析
 * 错误和代码缺陷则由消费骨架走 DLQ。这个分类不能由五类 worker 各自实现，否则会出现同一失败在不同 worker
 * 上出现“重试”与“丢入 DLQ”两种终态。
 */
final class TaskConsumerFailurePolicy {

  private TaskConsumerFailurePolicy() {}

  static boolean isTransientOrchestratorFailure(Throwable throwable) {
    for (Throwable current = throwable; current != null; current = current.getCause()) {
      if (current instanceof HttpServerErrorException
          || current instanceof ResourceAccessException
          || current instanceof ConnectException
          || current instanceof SocketTimeoutException
          || current instanceof UnknownHostException) {
        return true;
      }
    }
    return false;
  }
}
