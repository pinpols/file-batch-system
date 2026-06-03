package com.example.batch.orchestrator.service.failure;

import com.example.batch.common.enums.FailureClass;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.Texts;
import java.sql.SQLException;
import java.sql.SQLTransientException;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

/**
 * ADR-012 失败分类兜底链。
 *
 * <p>分类来源由近及远（{@link #classify} 单一入口逐级决定）：
 *
 * <ol>
 *   <li>worker 上报 {@link
 *       com.example.batch.orchestrator.controller.request.TaskExecutionReportDto#getFailureClass()}
 *       —— worker 业务侧最知根因；
 *   <li>{@link BizException#getFailureClass()} —— 业务方 throw 时显式声明；
 *   <li>本类的 exception/SQL state 兜底分类器 —— 经验规则；
 *   <li>都不知道 → {@link FailureClass#UNKNOWN}（ops review 信号，不是 bug）。
 * </ol>
 *
 * <p>注意：本类 <b>只读 + 决策</b>，永远不修业务状态。状态变更由调用方在终态推进路径写入。
 */
@Slf4j
@Component
public class FailureClassifier {

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
          reportedClassCode);
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
    // 3) 兜底分类（按异常类型 / SQL state）
    return classifyByThrowable(throwable);
  }

  /** 单参数变体，仅看异常（典型 orchestrator 内部 catch 路径）。 */
  public FailureClass classify(Throwable throwable) {
    return classify(null, throwable);
  }

  private FailureClass classifyByThrowable(Throwable throwable) {
    Throwable cursor = throwable;
    while (cursor != null) {
      // TIMEOUT 类（多见于 query timeout / Kafka send timeout / HTTP read timeout）
      if (cursor instanceof TimeoutException
          || cursor instanceof QueryTimeoutException
          || isJavaSqlTimeout(cursor)) {
        return FailureClass.TIMEOUT;
      }
      // INFRASTRUCTURE 类
      if (cursor instanceof TransientDataAccessException
          || cursor instanceof SQLTransientException
          || cursor instanceof OptimisticLockingFailureException
          || cursor instanceof ResourceAccessException
          || cursor instanceof DataAccessException) {
        return FailureClass.INFRASTRUCTURE;
      }
      if (cursor instanceof SQLException sql) {
        return classifyBySqlState(sql.getSQLState());
      }
      cursor = cursor.getCause();
    }
    return FailureClass.UNKNOWN;
  }

  private boolean isJavaSqlTimeout(Throwable t) {
    return t instanceof java.sql.SQLTimeoutException;
  }

  /** 经典 PG SQLState 大分类。详见 https://www.postgresql.org/docs/current/errcodes-appendix.html */
  private FailureClass classifyBySqlState(String sqlState) {
    if (!Texts.hasText(sqlState)) {
      return FailureClass.UNKNOWN;
    }
    // 08xxx connection exception / 53xxx insufficient resources / 57xxx operator intervention
    // / 58xxx system error  → INFRASTRUCTURE
    if (sqlState.startsWith("08")
        || sqlState.startsWith("53")
        || sqlState.startsWith("57")
        || sqlState.startsWith("58")) {
      return FailureClass.INFRASTRUCTURE;
    }
    // 23xxx integrity constraint violation → DATA_QUALITY
    if (sqlState.startsWith("23")) {
      return FailureClass.DATA_QUALITY;
    }
    // 40001 serialization_failure / 40P01 deadlock → INFRASTRUCTURE（瞬态可重试）
    if (sqlState.startsWith("40")) {
      return FailureClass.INFRASTRUCTURE;
    }
    // 22xxx data exception → DATA_QUALITY (转换失败 / 越界等)
    if (sqlState.startsWith("22")) {
      return FailureClass.DATA_QUALITY;
    }
    // 42xxx syntax error / access rule violation → CONFIG
    if (sqlState.startsWith("42")) {
      return FailureClass.CONFIG;
    }
    return FailureClass.UNKNOWN;
  }
}
