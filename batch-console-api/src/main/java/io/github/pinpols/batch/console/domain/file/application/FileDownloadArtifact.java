package io.github.pinpols.batch.console.domain.file.application;

import java.io.InputStream;

/** 下载内容与展示信息；内容流由消费方关闭。 */
public record FileDownloadArtifact(
    InputStream content, String fileName, String contentType, boolean unknownLength) {}
