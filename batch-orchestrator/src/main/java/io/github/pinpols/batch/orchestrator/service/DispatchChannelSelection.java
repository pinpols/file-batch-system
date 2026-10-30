package io.github.pinpols.batch.orchestrator.service;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.List;
import java.util.Map;

/** 普通分发在启动时显式指定的渠道，选择顺序与调度及 Worker 一致。 */
final class DispatchChannelSelection {

  private DispatchChannelSelection() {}

  static String explicitChannel(Map<String, Object> params) {
    for (String key : List.of("channelCode", "dispatchChannelCode", "targetChannelCode")) {
      Object value = params.get(key);
      if (EmptyChecks.isNotNull(value)) {
        String channel = String.valueOf(value);
        if (!channel.isBlank()) { // empty-check: allow - 保留 JDK 字符串空白判定
          return channel.trim();
        }
      }
    }
    return null;
  }
}
