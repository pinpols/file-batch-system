package com.example.batch.worker.imports.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ImportScannerPropertiesTest {

  @Test
  @DisplayName("done-file 后缀默认 .done(向后兼容承诺,不可静默改)")
  void doneFileSuffix_defaultsToDotDone() {
    assertThat(new ImportScannerProperties().getDoneFileSuffix()).isEqualTo(".done");
  }

  @Test
  @DisplayName("done-file 后缀可配成 .chk 等上游协议后缀")
  void doneFileSuffix_isConfigurable() {
    ImportScannerProperties props = new ImportScannerProperties();
    props.setDoneFileSuffix(".chk");
    assertThat(props.getDoneFileSuffix()).isEqualTo(".chk");
  }

  @Test
  @DisplayName("标记命名默认 APPEND_FULL_NAME(全名+后缀,统一无歧义)")
  void doneFileNaming_defaultsToAppendFullName() {
    assertThat(new ImportScannerProperties().getDoneFileNaming()).isEqualTo("APPEND_FULL_NAME");
  }

  @Test
  @DisplayName("标记格式默认 MARKER(空标记,向后兼容);MANIFEST 模式才启用 JSON 强校验")
  void doneFileFormat_defaultsToMarker() {
    assertThat(new ImportScannerProperties().getDoneFileFormat()).isEqualTo("MARKER");
  }
}
