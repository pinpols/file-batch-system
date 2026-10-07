package io.github.pinpols.batch.orchestrator.service.failure;

import io.github.pinpols.batch.common.enums.FailureClass;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.LogSanitizer;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.service.failure.TechnicalFailureClassificationPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ADR-012 失败分类回退链。
 *
 * <p>分类来源由近及远（{@link #classify} 单一入口逐级决定）：
 *
 * <ol>
 *   <li>worker 上报 {@link
 *       io.github.pinpols.batch.orchestrator.controller.request.TaskExecutionReportDto#getFailureClass()}
 *       —— worker 业务侧最知根因；
 *   <li>{@link BizException#getFailureClass()} —— 业务方 throw 时显式声明；
 *   <li>技术失败分类端口的 exception/SQL state 回退分类器 —— 经验规则；
 *   <li>都不知道 → {@link FailureClass#UNKNOWN}（ops review 信号，不是 bug）。
 * </ol>
 *
 * <p>注意：本类 <b>只读 + 决策</b>，永远不修业务状态。状态变更由调用方在终态推进路径写入。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FailureClassifier {

  private final TechnicalFailureClassificationPort technicalFailureClassification;

  /**
   * 单一入口。
   *
   * @param reportedClassCode worker 上报字符串（可空）
   * @param throwable 异常对象（可空 — worker 路径只看 reported；orchestrator 内部异常路径只看 throwable）
   * @return 永不为 null；最差返回 {@link FailureClass#UNKNOWN}
   */
  public FailureClass classify(String reportedClassCode, Throwable throwable) {
    // 1) worker 显式上报优先(用 fromCodeOrUnknown 安全变体,避免 fromCode 抛 BizException 中断分类)
    if (Texts.hasText(reportedClassCode)) {
      FailureClass reported = FailureClass.fromCodeOrUnknown(reportedClassCode);
      if (reported != FailureClass.UNKNOWN) {
        return reported;
      }
      log.warn(
          "worker reported unknown failure_class '{}', falling back to classifier",
          LogSanitizer.value(reportedClassCode));
    }
    if (throwable == null) {
      return FailureClass.UNKNOWN;
    }
    // 2) BizException 携带显式 class
    Throwable cursor = throwable;
    while (cursor != null) {
      if (cursor instanceof BizException biz && biz.getFailureClass() != null) {
        return biz.getFailureClass();
      }
      cursor = cursor.getCause();
    }
    // 3) 委托基础设施适配器按异常类型 / SQL state 回退分类。
    return technicalFailureClassification.classify(throwable);
  }

  /** 单参数变体，仅看异常（典型 orchestrator 内部 catch 路径）。 */
  public FailureClass classify(Throwable throwable) {
    return classify(null, throwable);
  }
}
