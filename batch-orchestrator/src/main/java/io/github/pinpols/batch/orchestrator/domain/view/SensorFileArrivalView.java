package io.github.pinpols.batch.orchestrator.domain.view;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** 文件到达 Sensor 最新命中的固定查询投影。 */
public record SensorFileArrivalView(
    Long fileId,
    String fileName,
    OffsetDateTime arrivalTime,
    String storagePath,
    LocalDate bizDate,
    String fileGroupCode) {

  /** 仅在 Sensor 动态输出边界恢复原协议字段。 */
  public Map<String, Object> toProbeOutput() {
    Map<String, Object> output = new LinkedHashMap<>();
    output.put("fileId", fileId);
    output.put("fileName", fileName);
    output.put("arrivalTime", arrivalTime);
    output.put("storagePath", storagePath);
    output.put("bizDate", bizDate);
    output.put("fileGroupCode", fileGroupCode);
    return output;
  }
}
