package io.github.pinpols.batch.orchestrator.application.service.failure;

import io.github.pinpols.batch.common.enums.FailureClass;

/**
 * 技术异常分类端口。
 *
 * <p>应用层只依赖归一化后的失败类别；JDBC、Spring DAO 与 HTTP 客户端异常类型由 infrastructure 适配器识别。
 */
public interface TechnicalFailureClassificationPort {

  /** 根据异常因果链返回技术失败类别；无法识别时返回 {@link FailureClass#UNKNOWN}。 */
  FailureClass classify(Throwable throwable);
}
