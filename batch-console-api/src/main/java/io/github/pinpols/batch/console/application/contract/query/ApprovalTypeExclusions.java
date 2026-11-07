package io.github.pinpols.batch.console.application.contract.query;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 解析审批查询的排除类型。只接受大写类型代码，避免把任意字符串送进查询条件。 */
public final class ApprovalTypeExclusions {

  private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
  private static final int MAX_TYPES = 8;

  private ApprovalTypeExclusions() {}

  public static List<String> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    List<String> types = new ArrayList<>();
    for (String part : raw.split(",", -1)) {
      String code = part.trim();
      if (code.isEmpty()) {
        continue;
      }
      boolean tooMany = types.size() == MAX_TYPES;
      if (tooMany || !CODE.matcher(code).matches()) {
        throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.approval.exclude_type_invalid");
      }
      if (!types.contains(code)) {
        types.add(code);
      }
    }
    return List.copyOf(types);
  }
}
