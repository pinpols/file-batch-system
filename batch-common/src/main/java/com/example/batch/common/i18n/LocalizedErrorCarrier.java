package com.example.batch.common.i18n;

/**
 * i18n 错误三元组的 carrier 抽象。所有持久化层 entity / Mapper Param / 跨进程 DTO 只要有 {@code errorMessage + errorKey
 * + errorArgs} 三字段(JavaBean / record / Lombok @Data 均可), 实现本接口即可被 {@link
 * LocalizedErrorRenderer#render(LocalizedErrorCarrier)} 直接渲染, 避免每个 callsite 解构三个 getter。
 *
 * <p>设计理由:
 *
 * <ul>
 *   <li>read-only:只暴露 getter,持久化层 entity 还是用 @Data 自带的 setter / Mapper Param 用
 *       {@code @Getter @Builder} 不可变模式。
 *   <li>不嵌套 record:保持字段平铺以兼容 MyBatis ResultMap 与前端 JSON 契约 ({@code errorMessage / errorKey /
 *       errorArgs} 不变)。
 *   <li>{@link #toLocalizedError()} default 把三字段聚合为内存模型 {@link LocalizedError}, Renderer 与业务逻辑用聚合
 *       record,持久化用平铺三字段。
 * </ul>
 *
 * <p>实现类(15+):JobTaskEntity / WorkflowNodeRunEntity / EventDeliveryLogEntity /
 * JobStepInstanceEntity / CompensationCommandEntity / FileErrorRecordEntity / FinishTaskParam /
 * UpdateNodeRunStatusParam / UpdateStepProgressParam / UpdateTaskStatusParam /
 * TaskExecutionReportDto / TaskExecutionReport / 等。
 */
public interface LocalizedErrorCarrier {

  /** 写入时已渲染好的字符串(老 literal / 第三方异常 / fallback 用)。 */
  String getErrorMessage();

  /** i18n message key;非 null 表示读路径应按当前 Locale 重渲染。 */
  String getErrorKey();

  /** i18n 占位符参数 JSON 数组(与 messages.properties 中 {0}/{1}/... 顺序对应)。 */
  String getErrorArgs();

  /** 聚合成内存 record 形式,便于函数式传递与单元测试 mock。 */
  default LocalizedError toLocalizedError() {
    return new LocalizedError(getErrorKey(), getErrorArgs(), getErrorMessage());
  }
}
