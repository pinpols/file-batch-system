package io.github.pinpols.batch.worker.exports.stage.format;

/**
 * 续跑时首页为空且模板与插件都未声明列布局，无法确定残文件已写表头/行的格式。
 *
 * <p>此时禁止截断残文件或按零行登记：保留位点与残文件供诊断，由调用方转成可识别的失败。
 */
public final class ExportResumeLayoutUnknownException extends RuntimeException {

    private final String formatType;

    public ExportResumeLayoutUnknownException(String formatType) {
        super("export GENERATE resume cannot reconstruct " + formatType
                + " column layout: first page is empty and neither template nor plugin declares columns;"
                + " checkpoint and partial file kept");
        this.formatType = formatType;
    }

    public String formatType() {
        return formatType;
    }
}
