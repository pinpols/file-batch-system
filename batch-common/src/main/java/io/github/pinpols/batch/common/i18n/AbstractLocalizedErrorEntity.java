package io.github.pinpols.batch.common.i18n;

import lombok.Getter;
import lombok.Setter;

/**
 * 持久化层 entity / DTO 的通用 i18n 错误三字段父类。子类 {@code extends AbstractLocalizedErrorEntity} 即自动获得
 * errorMessage + errorKey + errorArgs 字段 + getter/setter,无需重复声明,字段命名漂移风险消除。
 *
 * <p>不适用场景(仍走 implements {@link LocalizedErrorCarrier} 直接桥接):
 *
 * <ul>
 *   <li>Java record(无法 extends class):{@code TaskOutcomeCommand}
 *   <li>不可变 {@code @Builder} Mapper Param(继承会破坏 final 字段语义):{@code FinishTaskParam} 等
 *   <li>字段命名变体:{@code RetryScheduleEntity.lastErrorMessage / lastErrorKey / lastErrorArgs}
 * </ul>
 *
 * <p>MyBatis 行为:ResultMap 通过反射写 inherited setter,继承字段无需改 mapper.xml。Lombok @Data 子类 默认
 * {@code @EqualsAndHashCode(callSuper=false)},entity equality 通常基于 id 主键,父字段不参与 equality 不影响业务正确性。
 */
@Getter
@Setter
public abstract class AbstractLocalizedErrorEntity implements LocalizedErrorCarrier {

  /** 写入时已渲染好的字符串(老 literal / 第三方异常 / fallback 用)。 */
  private String errorMessage;

  /** i18n message key,V77+ 写入;读路径按当前 Locale 渲染时优先于 errorMessage。 */
  private String errorKey;

  /** i18n 占位符参数 JSON 数组,与 messages.properties {0}/{1}/... 顺序对应。 */
  private String errorArgs;
}
