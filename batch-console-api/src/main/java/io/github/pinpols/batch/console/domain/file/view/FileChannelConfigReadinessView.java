package io.github.pinpols.batch.console.domain.file.view;

import lombok.Builder;

/** 文件渠道就绪检查所需的固定列投影。 */
@Builder
public record FileChannelConfigReadinessView(
    String channelCode,
    String channelType,
    String authType,
    Boolean enabled,
    String configJson,
    String targetEndpoint) {}
