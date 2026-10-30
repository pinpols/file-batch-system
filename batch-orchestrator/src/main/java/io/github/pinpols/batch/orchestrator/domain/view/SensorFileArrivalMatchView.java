package io.github.pinpols.batch.orchestrator.domain.view;

import java.time.OffsetDateTime;

/** 文件到达 Sensor 调试查询的固定三列投影。 */
public record SensorFileArrivalMatchView(
    Long fileId, String fileName, OffsetDateTime arrivalTime) {}
