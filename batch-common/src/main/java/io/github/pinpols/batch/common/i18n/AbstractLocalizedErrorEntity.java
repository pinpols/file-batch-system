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
 * <p>MyBatis 通过继承的 setter 写入错误字段，ResultMap 无需复制字段定义。本抽象载体只生成访问器，
 * 不额外引入对象身份或相等性实现；具体实体沿用自身的相等性契约。Lombok {@code @Data} 子类默认不将父类字段纳入
 * {@code equals/hashCode}，需要包含错误信息时应在具体实体明确声明，不能假定默认只比较主键。
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
