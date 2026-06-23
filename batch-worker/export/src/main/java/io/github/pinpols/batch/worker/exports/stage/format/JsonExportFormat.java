package io.github.pinpols.batch.worker.exports.stage.format;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 生成单文档 JSON 文件，结构为： {@code {"snapshot":…, "batch":…, "details":[…]}}。 */
@Component
public class JsonExportFormat extends AbstractExportFormat {

  public JsonExportFormat(ObjectMapper objectMapper) {
    super(objectMapper);
  }

  @Override
  public String formatType() {
    return "JSON";
  }

  @Override
  public long generate(ExportFormatContext ctx) throws Exception {
    // ADR-038 P3:openExportFile 按续跑状态决定 truncate(首跑) / truncate-to-offset+append(续跑)。
    try (ResumableExportFile file = openExportFile(ctx)) {
      return writeJson(file, ctx);
    }
  }

  private long writeJson(ResumableExportFile file, ExportFormatContext ctx) throws Exception {
    BufferedWriter writer = file.writer();
    // 续跑时残文件已含前缀 {"snapshot":…,"batch":…,"details":[ + 若干 detail,不可重写前缀。
    if (!isResuming(ctx)) {
      Object snapshot = ctx.jobContext().getAttributes().get(PipelineRuntimeKeys.EXPORT_SNAPSHOT);
      if (snapshot == null) {
        snapshot = Map.of();
      }
      writer.write("{\"snapshot\":");
      writeJsonValue(writer, snapshot);
      writer.write(",\"batch\":");
      writeJsonValue(writer, ctx.batch());
      writer.write(",\"details\":[");
    }
    long recordCount =
        generatePaged(
            ctx,
            null,
            file::flushAndSync,
            (batch, detail, rowIndex) -> {
              // rowIndex 续跑时接续跑行数往后排,故 >0 自然在续写首行前补逗号,衔接残文件末尾的 …,detailN。
              if (rowIndex > 0) {
                writer.write(",");
              }
              writeJsonValue(writer, detail);
              if (ctx.chunkSize() > 0 && (rowIndex + 1) % ctx.chunkSize() == 0) {
                writer.flush();
              }
            });
    // 后缀只在生成整体完成时写一次(首跑/续跑皆然);崩溃残文件因尚未写后缀,续跑 truncate 后续写,收尾再补 ]}。
    writer.write("]}");
    return recordCount;
  }

  private void writeJsonValue(Writer writer, Object value) throws IOException {
    try (JsonGenerator generator = objectMapper.getFactory().createGenerator(writer)) {
      generator.configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false);
      objectMapper.writeValue(generator, value);
    }
  }
}
