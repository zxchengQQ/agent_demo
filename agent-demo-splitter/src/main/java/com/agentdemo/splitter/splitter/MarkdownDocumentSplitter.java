package com.agentdemo.splitter.splitter;

import com.agentdemo.splitter.config.SplitterProperties;
import com.agentdemo.splitter.loader.ParsedDocument;
import com.agentdemo.splitter.splitter.util.CascadeSplitter;
import com.agentdemo.splitter.splitter.util.ChunkMerger;
import com.agentdemo.splitter.tokenizer.SplitterTokenEstimator;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown 专属分割器
 * <p>
 * 业务含义：使用 commonmark-java + GFM Tables 扩展解析 Markdown AST，
 * 按 Heading 节点分割为多个 Section。FencedCodeBlock 和 TableBlock 作为
 * 原子单元不被切断。超大 Section 通过原子单元贪心打包 + CascadeSplitter 切分。
 * metadata 写入 headerLevel 和 headerText，保留章节结构信息。
 */
@Slf4j
@Component
public class MarkdownDocumentSplitter implements TypedDocumentSplitter {

    private final CascadeSplitter cascadeSplitter;
    private final ChunkMerger chunkMerger;
    private final SplitterProperties properties;
    private final SplitterTokenEstimator estimator;
    private final Parser parser;

    public MarkdownDocumentSplitter(SplitterTokenEstimator estimator,
                                    SplitterProperties properties) {
        this.estimator = estimator;
        this.properties = properties;
        this.cascadeSplitter = new CascadeSplitter(estimator);
        this.chunkMerger = new ChunkMerger(estimator);

        List<Extension> extensions = List.of(TablesExtension.create());
        this.parser = Parser.builder().extensions(extensions).build();
    }

    @Override
    public String supportedFormat() {
        return "md";
    }

    @Override
    public List<TextSegment> split(ParsedDocument parsedDocument) {
        String text = parsedDocument.getText();
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        // 解析 Markdown 为 AST
        Node document = parser.parse(text);

        // 按 Heading 分割为多个 Section
        List<Section> sections = collectSections(document);

        // 处理每个 Section
        SplitterProperties.ChunkConfig config = properties.getMd();
        int maxSize = config.getSize();
        int overlap = config.getOverlap();

        List<TextSegment> result = new ArrayList<>();
        for (Section section : sections) {
            String sectionText = section.fullText();
            int sectionTokens = estimator.estimate(sectionText);

            if (sectionTokens <= maxSize) {
                result.add(createSegment(sectionText, section));
            } else {
                // 超大 Section：按原子单元贪心打包，单原子超限时调用 CascadeSplitter
                List<String> chunks = packAtomicUnits(section.units, maxSize, overlap);
                for (String chunk : chunks) {
                    result.add(createSegment(chunk, section));
                }
            }
        }

        // 合并过短块：全局合并（collectSections 已保证 Section 语义完整性，携带父标题后无需按 headerText 分组）
        int minSize = config.getMinSize();
        return chunkMerger.merge(result, minSize, maxSize);
    }

    // ===== AST 遍历与 Section 收集 =====

    /**
     * 遍历 AST 顶层块节点，按 Heading 分割为 Section
     * <p>
     * 当父标题后紧跟子标题时，父标题 Section 仅有标题行无内容，
     * 此时将父标题行携带到子标题 Section 作为内容前缀，避免产生碎片块。
     */
    private List<Section> collectSections(Node document) {
        List<Section> sections = new ArrayList<>();
        Section current = new Section();

        for (Node child = document.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Heading heading) {
                // 遇到标题：判断当前 Section 是否需要保存
                String carriedHeading = null;
                if (current.hasContent()) {
                    if (current.units.size() > 1) {
                        // Section 有标题+内容：保存为独立 Section
                        sections.add(current);
                    } else {
                        // Section 仅有标题行（无内容）：不保存，将标题行携带到新 Section
                        carriedHeading = current.units.get(0);
                    }
                }
                current = new Section();
                current.headerLevel = heading.getLevel();
                current.headerText = getInlineText(heading);
                String headingMd = "#".repeat(heading.getLevel()) + " " + current.headerText + "\n";
                if (carriedHeading != null) {
                    current.addUnit(carriedHeading);
                }
                current.addUnit(headingMd);
            } else {
                // 非标题块节点：作为原子单元加入当前 Section
                String blockText = renderBlock(child);
                if (!blockText.isEmpty()) {
                    current.addUnit(blockText);
                }
            }
        }

        if (current.hasContent()) {
            sections.add(current);
        }

        return sections;
    }

    // ===== 原子单元打包 =====

    /**
     * 贪心打包原子单元，确保代码块/表格不被切断
     * <p>
     * 单个原子单元超过 maxSize 时调用 CascadeSplitter 强制切分。
     */
    private List<String> packAtomicUnits(List<String> units, int maxSize, int overlap) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentTokens = 0;

        for (String unit : units) {
            int unitTokens = estimator.estimate(unit);

            if (unitTokens > maxSize) {
                // 原子单元本身超限：先刷新当前块，再调用 CascadeSplitter 切分
                if (current.length() > 0) {
                    result.add(current.toString());
                    current = new StringBuilder();
                    currentTokens = 0;
                }
                result.addAll(cascadeSplitter.split(unit, maxSize, overlap));
            } else if (currentTokens + unitTokens > maxSize) {
                // 加入当前原子单元会超限：刷新当前块，开始新块
                result.add(current.toString());
                current = new StringBuilder(unit);
                currentTokens = unitTokens;
            } else {
                current.append(unit);
                currentTokens += unitTokens;
            }
        }

        if (current.length() > 0) {
            result.add(current.toString());
        }

        return result;
    }

    // ===== 节点渲染（AST -> Markdown 文本）=====

    /**
     * 将块级节点渲染为 Markdown 文本
     */
    private String renderBlock(Node node) {
        if (node instanceof FencedCodeBlock code) {
            return "```" + code.getInfo() + "\n" + code.getLiteral() + "```\n";
        } else if (node instanceof IndentedCodeBlock code) {
            return code.getLiteral();
        } else if (node instanceof TableBlock table) {
            return renderTable(table);
        } else if (node instanceof Paragraph) {
            return getInlineText(node) + "\n\n";
        } else if (node instanceof BulletList || node instanceof OrderedList) {
            return renderList(node, node instanceof OrderedList);
        } else if (node instanceof BlockQuote) {
            return renderBlockQuote(node);
        } else if (node instanceof ThematicBreak) {
            return "---\n";
        } else if (node instanceof HtmlBlock html) {
            return html.getLiteral() + "\n";
        } else {
            return getInlineText(node) + "\n";
        }
    }

    /**
     * 渲染 GFM 表格为 Markdown 文本
     */
    private String renderTable(TableBlock table) {
        List<List<String>> rows = new ArrayList<>();
        collectTableRows(table, rows);
        if (rows.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            sb.append("| ");
            sb.append(String.join(" | ", row));
            sb.append(" |\n");
            if (i == 0) {
                sb.append("|");
                for (int j = 0; j < row.size(); j++) {
                    sb.append(" --- |");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 递归收集表格行数据
     */
    private void collectTableRows(Node node, List<List<String>> rows) {
        if (node instanceof TableRow) {
            List<String> cells = new ArrayList<>();
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                if (child instanceof TableCell) {
                    cells.add(getInlineText(child).trim());
                }
            }
            rows.add(cells);
        } else {
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                collectTableRows(child, rows);
            }
        }
    }

    /**
     * 渲染列表为 Markdown 文本
     */
    private String renderList(Node node, boolean ordered) {
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof ListItem) {
                String prefix = ordered ? (index++) + ". " : "- ";
                String itemText = getInlineText(child).trim();
                sb.append(prefix).append(itemText).append("\n");
            }
        }
        sb.append("\n");
        return sb.toString();
    }

    /**
     * 渲染引用块为 Markdown 文本
     */
    private String renderBlockQuote(Node node) {
        String text = getInlineText(node).trim();
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            sb.append("> ").append(line).append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    /**
     * 递归收集节点内的内联文本（Text、Code、LineBreak 等）
     */
    private String getInlineText(Node node) {
        StringBuilder sb = new StringBuilder();
        collectInlineText(node, sb);
        return sb.toString();
    }

    private void collectInlineText(Node node, StringBuilder sb) {
        if (node instanceof Text text) {
            sb.append(text.getLiteral());
        } else if (node instanceof Code code) {
            sb.append("`").append(code.getLiteral()).append("`");
        } else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
            sb.append("\n");
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            collectInlineText(child, sb);
        }
    }

    // ===== 辅助 =====

    private TextSegment createSegment(String text, Section section) {
        Metadata metadata = new Metadata();
        if (section.headerLevel != null) {
            metadata = metadata.put("headerLevel", String.valueOf(section.headerLevel));
            metadata = metadata.put("headerText", section.headerText != null ? section.headerText : "");
        }
        return TextSegment.from(text, metadata);
    }

    /**
     * Section 数据结构：一个标题下的内容块集合
     */
    private static class Section {
        Integer headerLevel;
        String headerText;
        final List<String> units = new ArrayList<>();

        void addUnit(String text) {
            units.add(text);
        }

        boolean hasContent() {
            return !units.isEmpty();
        }

        String fullText() {
            return String.join("", units);
        }
    }
}
