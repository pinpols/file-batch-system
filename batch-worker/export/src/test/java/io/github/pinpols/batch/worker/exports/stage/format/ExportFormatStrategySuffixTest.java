package io.github.pinpols.batch.worker.exports.stage.format;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.FileTemplateFormat;
import io.github.pinpols.batch.common.file.ExportFileNameResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("导出格式策略: 生成文件后缀与对外文件名后缀保持一致")
class ExportFormatStrategySuffixTest {

  @ParameterizedTest(name = "{0}")
  @EnumSource(FileTemplateFormat.class)
  @DisplayName("每种文件格式的生成后缀与默认文件名解析结果一致")
  void shouldMatchPublishedFileNameSuffix(FileTemplateFormat format) {
    String publishedName = ExportFileNameResolver.resolve(ExportFileNameResolver.Input.builder()
        .fileFormatType(format.code())
        .bizType("biz")
        .bizDate("20261112")
        .batchNo("b1")
        .build());

    assertThat(publishedName).endsWith(ExportFormatStrategy.fileSuffixFor(format.code()));
  }
}
