package com.example.omniseek;

import org.junit.jupiter.api.Test;
import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.api.OpenDataLoaderPDF;

public class OpenDataLoaderTest {
    private static final String TEST_PDF = "D:\\train_Java\\OmniSeek\\src\\test\\java\\com\\example\\omniseek\\resources\\Localedit.pdf"; // 准备一个测试PDF
    private static final String OUTPUT_DIR = "D:\\train_Java\\OmniSeek\\src\\test\\java\\com\\example\\omniseek\\test-output";

    @Test
    public void testOpenDataLoader() throws Exception {
        Config config = new Config();
        config.setOutputFolder(OUTPUT_DIR);
        config.setGeneratePDF(true);
        config.setGenerateMarkdown(true);
        config.setGenerateHtml(true);
        try {
            // Process multiple files in one JVM invocation
            for (String pdf : new String[] { TEST_PDF }) {
                OpenDataLoaderPDF.processFile(pdf, config);
            }
        } finally {
            // Releases internal thread pools; call once at application exit, not between
            // batches
            OpenDataLoaderPDF.shutdown();
        }
    }

}
