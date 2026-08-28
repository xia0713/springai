package com.example.springai.chunking;

import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结构感知分块：按文档结构切分。
 * <p>
 * ① 先按 Markdown 标题（# ~ ######）切分成"章节"，标题归属其下方内容；
 * ② 超过 chunkSize 的章节再按段落（空行）细分；
 * ③ 仍超长的单个段落再硬切。
 * <p>
 * 适合 Markdown / 结构化文本，能保证标题与正文不分离，避免把语义单元拆散。
 */
public class StructureAwareSplitter extends TextSplitter {

    /** 匹配一行开头为 1~6 个 # 的 Markdown 标题（多行模式） */
    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,6}\\s+.*$");

    private final int chunkSize;

    public StructureAwareSplitter(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    @Override
    protected List<String> splitText(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        // ① 按标题切分（保留标题）
        List<String> sections = splitByHeadings(text);

        // ② 每个章节再细分
        for (String section : sections) {
            chunks.addAll(splitSection(section));
        }
        return chunks.stream().filter(s -> !s.isBlank()).toList();
    }

    /** 按标题行切分，标题保留在其所属内容的开头 */
    private List<String> splitByHeadings(String text) {
        List<Integer> headingStarts = new ArrayList<>();
        Matcher m = HEADING.matcher(text);
        while (m.find()) {
            headingStarts.add(m.start());
        }

        if (headingStarts.isEmpty()) {
            return List.of(text);
        }

        List<String> sections = new ArrayList<>();
        // 第一个标题之前的前言（若存在）
        if (headingStarts.get(0) > 0) {
            sections.add(text.substring(0, headingStarts.get(0)));
        }
        for (int i = 0; i < headingStarts.size(); i++) {
            int start = headingStarts.get(i);
            int end = (i + 1 < headingStarts.size()) ? headingStarts.get(i + 1) : text.length();
            sections.add(text.substring(start, end));
        }
        return sections;
    }

    /** 单个章节：超长则按段落（空行）细分，仍超长则硬切 */
    private List<String> splitSection(String section) {
        if (section.length() <= chunkSize) {
            return List.of(section.trim());
        }

        String[] paragraphs = section.split("\\n\\s*\\n");
        List<String> result = new ArrayList<>();
        StringBuilder buf = new StringBuilder();

        for (String p : paragraphs) {
            if (p.isBlank()) {
                continue;
            }
            // 单个段落本身就超长 → 硬切
            if (p.length() > chunkSize) {
                if (buf.length() > 0) {
                    result.add(buf.toString());
                    buf.setLength(0);
                }
                result.addAll(hardSplit(p));
                continue;
            }
            // 累加段落，接近 chunkSize 时收口
            if (buf.length() + p.length() + 2 > chunkSize && buf.length() > 0) {
                result.add(buf.toString());
                buf.setLength(0);
            }
            if (buf.length() > 0) {
                buf.append("\n\n");
            }
            buf.append(p);
        }
        if (buf.length() > 0) {
            result.add(buf.toString());
        }
        return result;
    }

    private List<String> hardSplit(String text) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            chunks.add(text.substring(start, end).trim());
            start = end;
        }
        return chunks;
    }
}
