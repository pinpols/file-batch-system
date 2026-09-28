package io.github.pinpols.batch.worker.imports.preprocess;

/** Import 预处理管道的步骤类型。 */
final class ImportPreprocessStepTypes {

  static final String UNZIP = "UNZIP";
  static final String GUNZIP = "GUNZIP";
  static final String UNTAR = "UNTAR";
  static final String UNTAR_GZ = "UNTAR_GZ";
  static final String AES_GCM_DECRYPT = "AES_GCM_DECRYPT";
  static final String VERIFY_DIGEST = "VERIFY_DIGEST";
  static final String VERIFY_RSA_SHA256 = "VERIFY_RSA_SHA256";
  static final String CHARSET_TRANSCODE = "CHARSET_TRANSCODE";

  private ImportPreprocessStepTypes() {}
}
