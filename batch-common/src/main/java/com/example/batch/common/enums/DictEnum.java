package com.example.batch.common.enums;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 所有业务字典枚举的统一契约：持久化使用 {@link #code()} 作为字段值，UI 展示使用 {@link #label()}。
 *
 * <p>新增枚举须实现本接口；想不对外暴露则加入 {@code ConsoleMetaEnumRegistrationTest#EXCLUDED}。
 *
 * <p>i18n: {@link #messageKey()} 返回 {@code dict.<simpleClassName>.<enumName>} 形式的 i18n key, console
 * 暴露 label 时优先按 Accept-Language 从 messageSource 解析,缺 key 时回退到本接口的 {@link #label()}(中文硬编码)。
 * 新增枚举若需支持英文, 在 {@code messages.properties} 加 key/value 即可;{@code messages_zh_CN.properties} 通常无需新增
 * 直接走 label() fallback。
 */
public interface DictEnum {

  String code();

  String label();

  /**
   * i18n 资源 key:形如 {@code dict.JobInstanceStatus.RUNNING}。仅枚举类型支持(标准用法),非枚举实现返回 null,callsite 自行回退到
   * {@link #label()}。
   */
  default String messageKey() {
    if (this instanceof Enum<?> e) {
      return "dict." + e.getDeclaringClass().getSimpleName() + "." + e.name();
    }
    return null;
  }

  /** 按 code 查枚举常量；code 为空或未匹配返回 null。调用者需要默认值或抛异常时，在 callsite 判断。 */
  static <E extends Enum<E> & DictEnum> E fromCode(Class<E> enumClass, String code) {
    if (code == null || code.isBlank()) {
      return null;
    }
    String normalized = code.trim();
    for (E value : enumClass.getEnumConstants()) {
      if (value.code().equalsIgnoreCase(normalized)) {
        return value;
      }
    }
    return null;
  }

  /** 返回枚举全部 code 的不可变集合，用于校验入参或构建下拉选项。 */
  static <E extends Enum<E> & DictEnum> Set<String> codes(Class<E> enumClass) {
    return Arrays.stream(enumClass.getEnumConstants())
        .map(DictEnum::code)
        .collect(Collectors.toUnmodifiableSet());
  }

  /** 返回枚举全部 label 的有序列表，保持枚举声明顺序。 */
  static <E extends Enum<E> & DictEnum> List<String> labels(Class<E> enumClass) {
    return Arrays.stream(enumClass.getEnumConstants()).map(DictEnum::label).toList();
  }

  /**
   * 返回枚举全部 code 的有序列表，保持枚举声明顺序。
   *
   * <p>{@link #codes(Class)} 返回 unordered Set，做集合校验（contains）够用，但生成 Excel 下拉、API 返回值需要
   * 业务认知的固定顺序时必须用本方法。
   */
  static <E extends Enum<E> & DictEnum> List<String> codeList(Class<E> enumClass) {
    return Arrays.stream(enumClass.getEnumConstants()).map(DictEnum::code).toList();
  }
}
