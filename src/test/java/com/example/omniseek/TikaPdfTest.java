package com.example.omniseek;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import technology.tabula.ObjectExtractor;
import technology.tabula.Page;
import technology.tabula.PageIterator;
import technology.tabula.RectangularTextContainer;
import technology.tabula.Table;
import technology.tabula.extractors.BasicExtractionAlgorithm;

/**
 * 使用 Tabula-java 提取 PDF 表格并转换为 Markdown 格式的测试类
 */
public class TikaPdfTest {

    /*
     * 面试知识点：PDF 解析中的“杂质”与“幻觉”
     * 
     * 1. 为什么会出现“文本文本...”混入表格？
     *    PDF 是一种页面描述语言，不是结构化语言。文字和线条在 PDF 内部是独立的坐标。
     *    当表格上方的文字距离表格边框过近时，解析算法会因坐标重叠将文字误判为单元格内容。
     * 
     * 2. 解决方案：
     *    - Stream 模式 (BasicExtractionAlgorithm): 忽略线条，根据文字的垂直对齐特征识别列，适合论文。
     *    - Lattice 模式 (SpreadsheetExtractionAlgorithm): 严格寻找表格线，适合有清晰边框的报表。
     */

    private static final Logger logger = LoggerFactory.getLogger(TikaPdfTest.class);

    @Test
    public void testPdfTableToMarkdown() {
        String filePath = "D:/快速访问/下载/teet.pdf";
        File file = new File(filePath);

        if (!file.exists()) {
            logger.warn("测试文件不存在: {}", filePath);
            return;
        }

        logger.info("开始使用 Tabula (Stream 模式) 提取表格: {}", filePath);

        try (PDDocument document = PDDocument.load(file)) {
            ObjectExtractor oe = new ObjectExtractor(document);
            // 切换为 BasicExtractionAlgorithm (Stream 模式)，对论文布局更友好
            BasicExtractionAlgorithm bea = new BasicExtractionAlgorithm(); 
            
            PageIterator pi = oe.extract();
            int pageNum = 0;
            
            while (pi.hasNext()) {
                Page page = pi.next();
                pageNum++;
                
                List<Table> tables = bea.extract(page);
                
                if (tables.isEmpty()) continue;

                for (int i = 0; i < tables.size(); i++) {
                    Table table = tables.get(i);
                    String markdown = convertToMarkdown(table);
                    
                    if (markdown.trim().isEmpty()) continue;

                    logger.info("--- 第 {} 页 - 表格 #{} (Stream 模式) ---\n\n{}\n", pageNum, i + 1, markdown);
                }
            }
            
        } catch (Exception e) {
            logger.error("Tabula 提取表格失败", e);
        }
    }

    /**
     * 将 Tabula Table 对象转换为 Markdown 字符串，并增加清洗逻辑
     */
    private String convertToMarkdown(Table table) {
        StringBuilder sb = new StringBuilder();
        List<List<RectangularTextContainer>> rows = table.getRows();
        
        // 过滤掉可能是由于算法误判导致的“杂质行”
        List<List<RectangularTextContainer>> cleanedRows = new ArrayList<>();
        for (List<RectangularTextContainer> row : rows) {
            String rowText = row.stream().map(c -> c.getText()).reduce("", String::concat);
            // 如果一行只有一个单元格且内容非常长，通常是误入的段落文字
            if (row.size() <= 1 && rowText.length() > 50) continue;
            if (rowText.trim().isEmpty()) continue;
            cleanedRows.add(row);
        }

        if (cleanedRows.isEmpty()) return "";

        for (int i = 0; i < cleanedRows.size(); i++) {
            List<RectangularTextContainer> cells = cleanedRows.get(i);
            
            sb.append("|");
            for (RectangularTextContainer cell : cells) {
                String text = cell.getText().replace("\r", "").replace("\n", " ").trim();
                sb.append(" ").append(text).append(" |");
            }
            sb.append("\n");

            if (i == 0) {
                sb.append("|");
                for (int j = 0; j < cells.size(); j++) sb.append(" --- |");
                sb.append("\n");
            }
        }
        
        return sb.toString();
    }
}
