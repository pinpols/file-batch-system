package io.github.pinpols.batch.worker.exports.stage.format;

public interface ExportFormatStrategy {

  String formatType();

  long generate(ExportFormatContext ctx) throws Exception;
}
