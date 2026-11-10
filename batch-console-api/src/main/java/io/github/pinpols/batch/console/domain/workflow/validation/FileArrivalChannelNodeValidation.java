package io.github.pinpols.batch.console.domain.workflow.validation;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.common.validation.FileArrivalChannelCode;
import java.util.Map;

/** 在工作流保存与配置导入时校验文件到达渠道字段，不要求渠道提前部署。 */
public final class FileArrivalChannelNodeValidation {
  private FileArrivalChannelNodeValidation() {}

  public static void validate(String nodeType, String nodeParams) {
    if (!"WAIT".equalsIgnoreCase(nodeType) || !Texts.hasText(nodeParams)) {
      return;
    }
    Map<?, ?> params;
    try {
      params = JsonUtils.fromJson(nodeParams, Map.class);
    } catch (IllegalArgumentException ignored) {
      // 原有节点 JSON 的校验和错误处理由对应写入入口负责。
      return;
    }
    if (EmptyChecks.isNull(params)
        || !"FILE_ARRIVAL".equals(params.get("sensor_type"))
        || !(params.get("sensor_spec") instanceof Map<?, ?> spec)) {
      return;
    }
    if (!FileArrivalChannelCode.isValid(spec.get("channelCode"))) {
      throw BizException.of(
          ResultCode.VALIDATION_ERROR, "error.workflow.sensor_channel_code_invalid");
    }
  }
}
