package io.github.pinpols.batch.console.web.response.config;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 两个配置发布版本的差异。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConfigReleaseDiffResponse(
    ConsoleConfigReleaseResponse releaseA,
    ConsoleConfigReleaseResponse releaseB,
    boolean payloadChanged,
    Object payloadA,
    Object payloadB,
    boolean grayScopeChanged,
    boolean statusChanged) {}
