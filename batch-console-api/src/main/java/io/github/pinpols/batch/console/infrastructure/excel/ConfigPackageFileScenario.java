package io.github.pinpols.batch.console.infrastructure.excel;

import io.github.pinpols.batch.common.enums.FileTemplateFormat;
import java.util.Arrays;
import java.util.Optional;

/** 场景化租户模板支持的文件方向与格式组合。 */
public enum ConfigPackageFileScenario {
  IMPORT_DELIMITED("IMPORT_DELIMITED", "IMPORT", FileTemplateFormat.DELIMITED),
  IMPORT_FIXED_WIDTH("IMPORT_FIXED_WIDTH", "IMPORT", FileTemplateFormat.FIXED_WIDTH),
  IMPORT_EXCEL("IMPORT_EXCEL", "IMPORT", FileTemplateFormat.EXCEL),
  IMPORT_XML("IMPORT_XML", "IMPORT", FileTemplateFormat.XML),
  IMPORT_JSON("IMPORT_JSON", "IMPORT", FileTemplateFormat.JSON),
  EXPORT_DELIMITED("EXPORT_DELIMITED", "EXPORT", FileTemplateFormat.DELIMITED),
  EXPORT_FIXED_WIDTH("EXPORT_FIXED_WIDTH", "EXPORT", FileTemplateFormat.FIXED_WIDTH),
  EXPORT_EXCEL("EXPORT_EXCEL", "EXPORT", FileTemplateFormat.EXCEL),
  EXPORT_JSON("EXPORT_JSON", "EXPORT", FileTemplateFormat.JSON);

  private final String code;
  private final String direction;
  private final FileTemplateFormat format;

  ConfigPackageFileScenario(String code, String direction, FileTemplateFormat format) {
    this.code = code;
    this.direction = direction;
    this.format = format;
  }

  public String code() {
    return code;
  }

  public String direction() {
    return direction;
  }

  public FileTemplateFormat format() {
    return format;
  }

  public static Optional<ConfigPackageFileScenario> fromCode(String code) {
    return Arrays.stream(values()).filter(value -> value.code.equals(code)).findFirst();
  }
}
