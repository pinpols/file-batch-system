package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 单个官方 Dispatch 渠道的安全事实快照,供启动审计、测试和文档共用。 */
record DispatchChannelSafetyProfile(
    String channelType,
    Set<DispatchChannelSafetyAttribute> attributes,
    String credentialHandling,
    String readbackSupport,
    Set<String> knownGaps) {

  DispatchChannelSafetyProfile {
    attributes = Set.copyOf(attributes);
    knownGaps = Set.copyOf(knownGaps);
  }

  Map<String, Object> toAuditMap() {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("attributes", attributes.stream().map(Enum::name).sorted().toList());
    view.put("credentialHandling", credentialHandling);
    view.put("readbackSupport", readbackSupport);
    view.put("knownGaps", knownGaps.stream().sorted().toList());
    return view;
  }
}
