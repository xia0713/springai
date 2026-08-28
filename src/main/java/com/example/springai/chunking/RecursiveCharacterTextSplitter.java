package com.example.springai.chunking;

import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.ArrayList;
import java.util.List;

/**
 * 递归字符分块：按分隔符优先级从"粗"到"细"递归切分。
 * <p>
 * 优先在段落、换行、句末标点等"自然边界"断开，只有当前级别分隔符切不开时才降级到更细的分隔符，
 * 最后兜底按固定长度硬切。相比固定大小，能尽量避免把一句话从中间切断。
 * <p>
 * 分隔符优先级：双换行 → 单换行 → 中文句末标点 → 英文句末标点 → 逗号 → 空格 → 硬切
 */
public class RecursiveCharacterTextSplitter extends TextSplitter {

    private final int chunkSize;
    private final int overlap;
    private final List<String> separators;

    public RecursiveCharacterTextSplitter(int chunkSize, int overlap) {
        this(chunkSize, overlap, defaultSeparators());
    }

    public RecursiveCharacterTextSplitter(int chunkSize, int overlap, List<String> separators) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize 必须 > 0");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
        this.separators = new ArrayList<>(separators);
    }

    private static List<String> defaultSeparators() {
        return List.of(
                "\n\n", "\n",
                "。", "！", "？", "；",
                ". ", "! ", "? ", "; ",
                "，", ", ", " "
        );
    }

    @Override
    protected List<String> splitText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return recursiveSplit(text, 0);
    }

    private List<String> recursiveSplit(String text, int sepIndex) {
        // 终止条件 1：文本足够短，直接作为一块
        if (text.length() <= chunkSize) {
            return text.isBlank() ? List.of() : List.of(text);
        }
        // 终止条件 2：没有更细的分隔符可用，硬切兜底
        if (sepIndex >= separators.size()) {
            return hardSplit(text);
        }

        String sep = separators.get(sepIndex);
        List<String> parts = splitKeepSeparator(text, sep);

        // 当前分隔符切不开（整段没有该分隔符），降级到下一级
        if (parts.size() <= 1) {
            return recursiveSplit(text, sepIndex + 1);
        }

        // 每个部分继续用更细的分隔符递归切
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            result.addAll(recursiveSplit(part, sepIndex + 1));
        }
        return result;
    }

    /** 按分隔符切分，但把分隔符保留在每段末尾（避免丢失标点/换行） */
    private List<String> splitKeepSeparator(String text, String sep) {
        if (sep.isEmpty()) {
            return List.of(text);
        }
        List<String> parts = new ArrayList<>();
        int start = 0;
        int idx;
        while ((idx = text.indexOf(sep, start)) >= 0) {
            parts.add(text.substring(start, idx + sep.length()));
            start = idx + sep.length();
        }
        if (start < text.length()) {
            parts.add(text.substring(start));
        }
        return parts;
    }

    /** 最后兜底：按固定长度硬切，带重叠 */
    private List<String> hardSplit(String text) {
        List<String> chunks = new ArrayList<>();
        int len = text.length();
        int start = 0;
        while (start < len) {
            int end = Math.min(start + chunkSize, len);
            chunks.add(text.substring(start, end).trim());
            if (end >= len) {
                break;
            }
            // overlap = 0 时也要保证 start 前进，避免死循环
            start = Math.max(end - overlap, start + 1);
        }
        return chunks;
    }
}
