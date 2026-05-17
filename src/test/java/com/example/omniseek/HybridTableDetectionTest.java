package com.example.omniseek;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 混合方案测试：基于 PDFBox 坐标 + 启发式规则 预判表格
 *
 * 核心思路：
 * 1. 提取每页文字块的 (x, y) 坐标
 * 2. 通过坐标聚类分析列对齐情况
 * 3. 结合启发式规则（关键词、数字密度）
 */
public class HybridTableDetectionTest {

    private static final Logger logger = LoggerFactory.getLogger(HybridTableDetectionTest.class);

    @Test
    public void testDetectTablesInPdf() {
        // 替换为测试 PDF 路径
        String filePath = "D:\\software\\zoeroData\\storage\\KNJUS5WR\\Gao 等 - 2026 - LoRA-Edit Controllable First-Frame-Guided Video Editing via Mask-Aware LoRA Fine-Tuning.pdf";
        File file = new File(filePath);

        if (!file.exists()) {
            logger.warn("测试文件不存在: {}", filePath);
            return;
        }

        logger.info("开始分析 PDF 表格结构: {}", filePath);

        List<Integer> pagesWithTables = new ArrayList<>();

        try (PDDocument document = PDDocument.load(file)) {
            int totalPages = document.getNumberOfPages();
            logger.info("PDF 总页数: {}", totalPages);

            for (int pageNum = 0; pageNum < totalPages; pageNum++) {
                logger.info("--- 正在分析第 {} 页 ---", pageNum + 1);

                PageAnalysisResult result = analyzePage(document, pageNum);

                if (result.isLikelyHasTable) {
                    pagesWithTables.add(pageNum + 1); // 记录页码（从1开始）
                    logger.warn("⚠️  第 {} 页大概率包含表格！", pageNum + 1);
                    logger.info("   - 行聚类数量: {}", result.uniqueRowCount);
                    logger.info("   - 文字块总数: {}", result.totalTextBlocks);
                    logger.info("   - 数字密度: {}%", String.format("%.2f", result.numberDensity * 100));

                    if (result.hasHeaderPattern) {
                        logger.info("   - ✅ 发现标准表头特征！");
                    }

                    if (result.keywordFound) {
                        logger.info("   - 发现表格关键词");
                        if (result.isKeywordVerifiedAsTable) {
                            logger.info("   - ✅ 关键词深度验证通过");
                        } else {
                            logger.info("   - ❌ 关键词深度验证未通过（上下文不像表格）");
                        }
                    }
                } else {
                    logger.info("✅ 第 {} 页看起来是纯文本", pageNum + 1);
                }
            }

            // 打印汇总信息
            logger.info("========================================");
            logger.info("PDF 分析完成！汇总结果：");
            logger.info("  总页数: {}", totalPages);
            logger.info("  含表格页数: {}", pagesWithTables.size());
            if (!pagesWithTables.isEmpty()) {
                logger.info("  含表格的页面: {}", pagesWithTables);
            } else {
                logger.info("  未检测到明显的表格页面。");
            }
            logger.info("========================================");

        } catch (Exception e) {
            logger.error("分析 PDF 失败", e);
        }
    }

    /**
     * 分析单页 PDF
     */
    private PageAnalysisResult analyzePage(PDDocument document, int pageIndex) throws Exception {
        PageAnalysisResult result = new PageAnalysisResult();

        // 第一步：快速提取纯文本，先做关键词预检查
        PDFTextStripper quickTextStripper = new PDFTextStripper();
        quickTextStripper.setStartPage(pageIndex + 1);
        quickTextStripper.setEndPage(pageIndex + 1);
        String pageText = quickTextStripper.getText(document);
        String lowerText = pageText.toLowerCase();

        // 检查1：精准的表头正则特征 (Table 1. 或 表 1：...)
        Pattern headerPattern = Pattern.compile("^(Table|表)\\s*\\d+[\\.:]?\\s+[A-Z]",
                Pattern.MULTILINE | Pattern.UNICODE_CASE);
        boolean hasHeaderPattern = headerPattern.matcher(pageText).find();

        // 只有 hasHeaderPattern 为 True，才继续分析；否则直接返回没有表格
        if (!hasHeaderPattern) {
            // 没有标准表头，直接判定为纯文本
            result.totalTextBlocks = 0;
            result.isLikelyHasTable = false;
            return result;
        }

        if (hasHeaderPattern) {
            logger.debug("   ✅ 发现标准表头特征！");
        }

        // 检查2：简单关键词
        boolean containsTableKeyword = lowerText.contains("表") || lowerText.contains("table")
                || lowerText.contains("ssim") || lowerText.contains("psnr");

        // 第二步：发现标准表头，才进行详细的坐标收集和分析
        result.keywordFound = containsTableKeyword;
        result.hasHeaderPattern = hasHeaderPattern; // 记录表头正则特征

        // 自定义 TextStripper 来收集坐标
        CoordinateCollector collector = new CoordinateCollector();
        collector.setStartPage(pageIndex + 1);
        collector.setEndPage(pageIndex + 1);
        collector.getText(document);

        List<TextPosition> textPositions = collector.getTextPositions();
        result.totalTextBlocks = textPositions.size();

        if (textPositions.isEmpty()) {
            result.isLikelyHasTable = false;
            return result;
        }

        // === 方案 2：基于坐标的布局分析 ===

        // 1. 收集所有文字块的 Y 坐标（行），进行去重和聚类
        Set<Float> uniqueRows = new HashSet<>();
        Set<Float> uniqueColumns = new HashSet<>();
        StringBuilder allText = new StringBuilder();

        float tolerance = 3.0f; // 坐标容差，3 像素内算同一行

        for (TextPosition tp : textPositions) {
            // 对 Y 坐标（行）
            float roundedY = Math.round(tp.getY() / tolerance) * tolerance;
            uniqueRows.add(roundedY);

            // 对 X 坐标（列）
            float roundedX = Math.round(tp.getX() / (tolerance * 2)) * (tolerance * 2);
            uniqueColumns.add(roundedX);

            allText.append(tp.getUnicode());
        }

        result.uniqueRowCount = uniqueRows.size();
        result.uniqueColumnCount = uniqueColumns.size();

        // === 方案 1：启发式规则（在已发现表头的基础上，锦上添花）===

        // 启发式 1：行数少但列数多（表格特征）
        // 表格往往一页只有 5-15 行，但有很多列
        boolean hasTableLayout = (result.uniqueRowCount > 2 && result.uniqueRowCount < 20
                && result.uniqueColumnCount > 3);

        // 启发式 2：数字密度高
        int digitCount = 0;
        for (char c : pageText.toCharArray()) {
            if (Character.isDigit(c)) {
                digitCount++;
            }
        }
        result.numberDensity = (double) digitCount / Math.max(pageText.length(), 1);
        boolean hasHighDigitDensity = result.numberDensity > 0.15; // 数字占比超过 15%

        // 如果发现关键词，进行深度二次确认
        if (containsTableKeyword) {
            result.isKeywordVerifiedAsTable = verifyTableByContext(pageText, textPositions);
        }

        // 综合判断：由于 hasHeaderPattern 已经为 True，直接判定有表格
        // 布局分析等其他特征仅作为辅助参考
        result.isLikelyHasTable = true;

        return result;
    }

    /**
     * 关键词深度确认：在发现 "表" 或 "Table" 后，进一步验证上下文
     */
    private boolean verifyTableByContext(String pageText, List<TextPosition> textPositions) {
        logger.debug("发现表格关键词，启动深度上下文验证...");

        // 深度确认 1：检查关键词周围是否有大量数字或短文本块（表格特征）
        int nearbyNumbers = 0;
        int nearbyShortTexts = 0;
        int nearbyLongTexts = 0;

        for (TextPosition tp : textPositions) {
            String text = tp.getUnicode().trim();
            if (text.length() > 0 && text.length() < 15) {
                nearbyShortTexts++;
            } else if (text.length() > 50) {
                nearbyLongTexts++;
            }
            if (text.matches(".*\\d.*")) {
                nearbyNumbers++;
            }
        }

        // 表格特征：短句多、长句少、数字多
        double shortTextRatio = (double) nearbyShortTexts / Math.max(textPositions.size(), 1);
        boolean hasTableTextPattern = shortTextRatio > 0.6 && nearbyNumbers > 5;

        // 深度确认 2：检查是否有典型的表格分隔线符号或格式化空格
        boolean hasFormattingPattern = pageText.contains("   ")
                || pageText.contains("  ")
                || pageText.matches(".*\\d+\\s+\\d+.*");

        if (hasTableTextPattern || hasFormattingPattern) {
            logger.info("✅ 关键词深度验证通过：检测到表格上下文特征");
            return true;
        }

        logger.info("❌ 关键词深度验证失败：上下文未显示明显表格特征");
        return false;
    }

    /**
     * 自定义 TextStripper 用于收集文字坐标
     */
    static class CoordinateCollector extends PDFTextStripper {
        private final List<TextPosition> textPositions = new ArrayList<>();

        public CoordinateCollector() throws Exception {
            super();
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            super.processTextPosition(text);
            textPositions.add(text);
        }

        public List<TextPosition> getTextPositions() {
            return textPositions;
        }
    }

    /**
     * 页面分析结果
     */
    static class PageAnalysisResult {
        boolean isLikelyHasTable = false;
        int totalTextBlocks = 0;
        int uniqueRowCount = 0;
        int uniqueColumnCount = 0;
        double numberDensity = 0.0;
        boolean keywordFound = false;
        boolean hasHeaderPattern = false; // 新增：是否发现标准表头正则特征
        boolean isKeywordVerifiedAsTable = false; // 新增：关键词深度确认结果
    }
}
