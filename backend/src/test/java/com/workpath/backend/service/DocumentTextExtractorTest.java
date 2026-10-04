package com.workpath.backend.service;

import com.workpath.backend.common.BusinessException;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 制度文档文本提取器测试。
 *
 * <p>覆盖三条路径：纯文本（UTF-8 / BOM / GBK 回退）、Word（POI 段落与表格）、
 * 以及拒绝路径（不支持的格式、空文件、损坏文件——异常必须是业务异常而不是
 * 原始的解析异常，否则接口会 500 且把技术细节暴露给管理端）。</p>
 */
class DocumentTextExtractorTest {

    private final DocumentTextExtractor extractor = new DocumentTextExtractor();

    @Test
    @DisplayName("txt（UTF-8）：按原文本提取，行结构保留")
    void extractsUtf8Txt() {
        String text = "1.1 外勤人员因公外出应提前报备。\n1.2 保持通讯畅通。";
        String out = extractor.extract("制度.txt", text.getBytes(StandardCharsets.UTF_8));
        assertThat(out).isEqualTo(text);
    }

    @Test
    @DisplayName("txt 带 UTF-8 BOM：BOM 被剥掉，不污染首行条款编号")
    void stripsUtf8Bom() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = "1.1 应提前报备".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, bytes, 0, bom.length);
        System.arraycopy(body, 0, bytes, bom.length, body.length);

        String out = extractor.extract("制度.txt", bytes);
        assertThat(out).isEqualTo("1.1 应提前报备");
        assertThat(out.charAt(0)).isNotEqualTo('\uFEFF');
    }

    @Test
    @DisplayName("GBK 编码的 txt：UTF-8 解码失败后按 GBK 回退")
    void fallsBackToGbk() {
        String text = "1.1 外勤人员因公外出应提前报备。";
        byte[] gbkBytes = text.getBytes(Charset.forName("GBK"));
        String out = extractor.extract("制度.txt", gbkBytes);
        assertThat(out).isEqualTo(text);
    }

    @Test
    @DisplayName("md 与 txt 同路径提取")
    void extractsMd() {
        String out = extractor.extract("制度.md", "# 标题\n1.1 内容".getBytes(StandardCharsets.UTF_8));
        assertThat(out).contains("1.1 内容");
    }

    @Test
    @DisplayName("docx：段落与表格文字都提取")
    void extractsDocx() throws Exception {
        byte[] bytes = buildDocxWithTable();
        String out = extractor.extract("制度.docx", bytes);
        assertThat(out).contains("1.1 外勤人员因公外出应提前报备。");
        assertThat(out).contains("1.2 保持通讯畅通。");
        assertThat(out).contains("表格里的规定也提取");
    }

    @Test
    @DisplayName("不支持的扩展名（pdf）直接拒绝，提示支持的格式")
    void rejectsUnsupported() {
        assertThatThrownBy(() -> extractor.extract("制度.pdf", "x".getBytes()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("docx");
    }

    @Test
    @DisplayName("无扩展名/空文件名拒绝")
    void rejectsNoExtension() {
        assertThatThrownBy(() -> extractor.extract("制度", "x".getBytes()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> extractor.extract(null, "x".getBytes()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("空文件拒绝")
    void rejectsEmptyFile() {
        assertThatThrownBy(() -> extractor.extract("制度.txt", new byte[0]))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("提取结果为空（空白 txt）拒绝——不产生空文档")
    void rejectsBlankResult() {
        assertThatThrownBy(() -> extractor.extract("制度.txt", "   \n\n  ".getBytes()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("损坏的 docx：包成业务异常，不把 POI 的解析异常裸露成 500")
    void brokenDocxBecomesBusinessException() {
        assertThatThrownBy(() -> extractor.extract("制度.docx", "not a zip".getBytes()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("扩展名大小写不敏感（.DOCX 也支持）")
    void extensionCaseInsensitive() {
        String out = extractor.extract("制度.TXT", "1.1 内容".getBytes(StandardCharsets.UTF_8));
        assertThat(out).contains("1.1 内容");
    }

    private static byte[] buildDocxWithTable() throws Exception {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("1.1 外勤人员因公外出应提前报备。");
            doc.createParagraph().createRun().setText("1.2 保持通讯畅通。");
            XWPFTable table = doc.createTable(1, 1);
            table.getRow(0).getCell(0).setText("表格里的规定也提取");
            doc.write(out);
            return out.toByteArray();
        }
    }
}
