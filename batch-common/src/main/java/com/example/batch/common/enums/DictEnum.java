package com.example.batch.common.enums;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 所有业务字典枚举的统一契约：持久化使用 {@link #code()} 作为字段值，UI 展示使用 {@link #label()}。
 *
 * <p>新增枚举须实现本接口；想不对外暴露则加入 {@code ConsoleMetaEnumRegistrationTest#EXCLUDED}。
 */
public interface DictEnum {

  String code();

  String label();

  /**
   * 按 code 查枚举常量；code 为空或未匹配返回 null。调用者需要默认值或抛异常时，在 callsite 判断。
   */
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
    return Arrays.stream(enumClass.getEnumConstants())
        .map(DictEnum::label)
        .toList();
  }
}
