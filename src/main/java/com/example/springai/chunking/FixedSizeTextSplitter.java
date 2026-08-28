package com.example.springai.chunking;

import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.ArrayList;
import java.util.List;

/**
 * 固定大小分块：按固定字符数硬切，可带重叠。
 * <p>
 * 最简单、最快，但可能把一句话从中间切断，适合对分块质量要求不高、追求速度的场景。
 */
public class FixedSizeTextSplitter extends TextSplitter {

    /** 每块字符数 */
    private final int chunkSize;

    /** 相邻块重叠字符数 */
    private final int overlap;

    public FixedSizeTextSplitter(int chunkSize, int overlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize 必须 > 0");
        }
        if (overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("overlap 必须 >= 0 且 < chunkSize");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    @Override
    protected List<String> splitText(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        int len = text.length();
        int start = 0;
        while (start < len) {
            int end = Math.min(start + chunkSize, len);
            chunks.add(text.substring(start, end).trim());
            if (end >= len) {
                break;
            }
            start = end - overlap;   // 向后回退 overlap 字符，形成重叠
        }
        return chunks.stream().filter(s -> !s.isBlank()).toList();
    }
}
