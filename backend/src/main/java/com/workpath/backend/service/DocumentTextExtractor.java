package com.workpath.backend.service;

import com.workpath.backend.common.BusinessException;
import com.workpath.backend.common.ResultCode;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 制度文档文本提取器：把本机上传的文件提取为纯文本，交给既有切分链路。
 *
 * <p>支持 .txt / .md / .docx 三类。PDF 不做——文本层提取质量不稳、
 * 扫描件还要 OCR，单独评估。</p>
 *
 * <p>两条实现口径：</p>
 * <ol>
 *   <li><b>txt/md 先按 UTF-8 严格解码、失败回退 GBK</b>：中文 Windows 的
 *       记事本"ANSI"实为 GBK，不做回退的话用户第一个 txt 就是乱码；</li>
 *   <li><b>docx 只取正文文本</b>：按 bodyElements 顺序提取段落与表格，
 *       行结构保留——条款切分器按行识别编号，行结构是切分质量的根基。
 *       自动编号（Word 列表样式）不在文字流里、无法还原，使用说明中
 *       要求编号手打进正文。</li>
 * </ol>
 *
 * <p>所有解析失败都翻译成 {@link BusinessException}：POI 的原始异常
 * （NotOfficeXmlFileException 等）裸抛会变成 500，管理端只能看到
 * "服务器内部错误"，排查无从下手。</p>
 */
@Component
public class DocumentTextExtractor {

    /** 支持的扩展名（小写） */
    public static final Set<String> SUPPORTED_EXTENSIONS = Set.of("txt", "md", "docx");

    /**
     * 提取文本。
     *
     * @param filename 原始文件名（按扩展名分发）
     * @param bytes    文件字节
     * @return 提取的纯文本，保证非空白
     * @throws BusinessException 不支持的类型 / 空文件 / 无法解析 / 提取结果为空
     */
    public String extract(String filename, byte[] bytes) {
        String ext = extensionOf(filename);
        if (!SUPPORTED_EXTENSIONS.contains(ext)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                    "仅支持 .txt / .md / .docx 文档，当前文件："
                            + (filename == null ? "未命名" : filename));
        }
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "文件内容为空");
        }
        String text = "docx".equals(ext) ? extractDocx(bytes) : decodeText(bytes);
        if (!StringUtils.hasText(text)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                    "未能从文档中读取到文本内容，请检查文件（纯图片/扫描件暂不支持）");
        }
        return text;
    }

    /** 取小写扩展名；无扩展名返回空串（一并走"不支持"拒绝路径）。 */
    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 纯文本解码：UTF-8 严格模式失败回退 GBK，最后剥掉可能的 UTF-8 BOM。 */
    private static String decodeText(byte[] bytes) {
        String text;
        try {
            text = strictDecode(bytes, StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            try {
                text = strictDecode(bytes, Charset.forName("GBK"));
            } catch (CharacterCodingException gbkFailure) {
                throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                        "无法识别文件编码（已尝试 UTF-8 与 GBK）");
            }
        }
        // UTF-8 BOM 会残留为 \uFEFF 首字符，不剥会污染首行条款编号识别
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        return text;
    }

    private static String strictDecode(byte[] bytes, Charset charset) throws CharacterCodingException {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    }

    /** docx 正文提取：按 bodyElements 顺序取段落与表格（表格按单元格段落逐行）。 */
    private static String extractDocx(byte[] bytes) {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            List<String> lines = new ArrayList<>();
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph p) {
                    addLine(lines, p.getText());
                } else if (element instanceof XWPFTable table) {
                    for (XWPFTableRow row : table.getRows()) {
                        for (XWPFTableCell cell : row.getTableCells()) {
                            for (XWPFParagraph cp : cell.getParagraphs()) {
                                addLine(lines, cp.getText());
                            }
                        }
                    }
                }
            }
            return String.join("\n", lines);
        } catch (Exception e) {
            // 加密文档、损坏文件、旧版 .doc 伪装成 .docx 等都会走到这里
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                    "无法解析该 Word 文档：" + e.getMessage());
        }
    }

    private static void addLine(List<String> lines, String text) {
        if (text != null && !text.isBlank()) {
            lines.add(text.strip());
        }
    }
}
