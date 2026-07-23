package io.github.pinpols.batch.worker.imports.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

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
    assertThat(new ImportScannerProperties().getDoneFileFormat())
        .isEqualTo(ImportScannerProperties.DoneFileFormat.MARKER);
  }

  @Test
  @DisplayName("MANIFEST 与历史 JSON 配置都启用 manifest 强校验")
  void doneFileFormat_manifestAndJsonAreCompatible() {
    assertThat(bindDoneFileFormat("MANIFEST").getDoneFileFormat().isManifest()).isTrue();
    assertThat(bindDoneFileFormat("JSON").getDoneFileFormat().isManifest()).isTrue();
  }

  @Test
  @DisplayName("标记格式支持大小写不敏感绑定")
  void doneFileFormat_bindingIsCaseInsensitive() {
    assertThat(bindDoneFileFormat("marker").getDoneFileFormat())
        .isEqualTo(ImportScannerProperties.DoneFileFormat.MARKER);
  }

  @Test
  @DisplayName("未知标记格式在配置绑定阶段显式失败")
  void doneFileFormat_unknownValueFailsBinding() {
    assertThatThrownBy(() -> bindDoneFileFormat("JSNO"))
        .isInstanceOf(BindException.class)
        .hasMessageContaining("done-file-format");
  }

  @Test
  @DisplayName("批次清单默认关闭,后缀默认 .batch.json(ADR-040)")
  void batchManifest_defaults() {
    ImportScannerProperties props = new ImportScannerProperties();
    assertThat(props.isBatchManifestEnabled()).isFalse();
    assertThat(props.getBatchManifestSuffix()).isEqualTo(".batch.json");
  }

  private static ImportScannerProperties bindDoneFileFormat(String value) {
    MapConfigurationPropertySource source =
        new MapConfigurationPropertySource(
            Map.of("batch.worker.import.scanner.done-file-format", value));
    return new Binder(source)
        .bind("batch.worker.import.scanner", Bindable.of(ImportScannerProperties.class))
        .orElseThrow(() -> new AssertionError("import scanner properties were not bound"));
  }
}
