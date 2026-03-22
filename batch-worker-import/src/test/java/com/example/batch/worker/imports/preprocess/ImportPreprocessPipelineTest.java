package com.example.batch.worker.imports.preprocess;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class ImportPreprocessPipelineTest {

    @Test
    void testingOpenShouldSkipChecksumAndCrypto() {
        byte[] out = ImportPreprocessPipeline.run(new byte[0], null, Map.of(), true);
        assertThat(out).isEmpty();
    }

    @Test
    void shouldGunzipWhenCompressTypeGzip() throws Exception {
        byte[] raw = "hello".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(bos)) {
            gos.write(raw);
        }
        byte[] gzipped = bos.toByteArray();
        Map<String, Object> template = Map.of("compress_type", "GZIP");
        byte[] out = ImportPreprocessPipeline.run(gzipped, null, template, true);
        assertThat(out).isEqualTo(raw);
    }
}
