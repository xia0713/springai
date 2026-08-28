package com.example.springai.chunking;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 语义分块：按句子 embedding 相似度找语义断点。
 * <p>
 * 思路：
 * ① 把文本切成句子，批量 embedding；
 * ② 计算相邻句子的余弦相似度；
 * ③ 相似度低于阈值的相邻处视为"语义断点"，在此断开；语义相近的句子聚成一块；
 * ④ 同时强制单块不超过 maxChunkSize，避免块过大。
 * <p>
 * 相比按长度/结构切分，能更贴合语义主题，但需要额外调用 embedding，成本更高。
 */
public class SemanticChunkingSplitter extends TextSplitter {

    private final EmbeddingModel embeddingModel;

    /** 单块最大字符数 */
    private final int maxChunkSize;

    /** 相邻句子相似度低于该值视为语义断点 */
    private final double breakThreshold;

    public SemanticChunkingSplitter(EmbeddingModel embeddingModel, int maxChunkSize, double breakThreshold) {
        this.embeddingModel = embeddingModel;
        this.maxChunkSize = maxChunkSize;
        this.breakThreshold = breakThreshold;
    }

    @Override
    protected List<String> splitText(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        // ① 切句
        List<String> sentences = splitSentences(text);
        if (sentences.size() <= 1) {
            return hardSplitIfNeeded(text);
        }

        // ② 批量 embedding
        List<float[]> embeddings = embeddingModel.embed(sentences);

        // ③ 计算相邻句相似度
        double[] similarities = new double[sentences.size() - 1];
        for (int i = 0; i < similarities.length; i++) {
            similarities[i] = cosine(embeddings.get(i), embeddings.get(i + 1));
        }

        // ④ 找断点：相似度 < 阈值 → 在第 i 句之后断开
        boolean[] isBreak = new boolean[sentences.size() - 1];
        for (int i = 0; i < similarities.length; i++) {
            isBreak[i] = similarities[i] < breakThreshold;
        }

        // ⑤ 按断点聚合句子成块，同时强制 maxChunkSize
        StringBuilder buf = new StringBuilder();
        int bufLen = 0;
        for (int i = 0; i < sentences.size(); i++) {
            String s = sentences.get(i);
            // 需要收口：语义断点，或再加一句就超长
            boolean closeHere = bufLen > 0 && (isBreak[i - 1] || bufLen + s.length() > maxChunkSize);
            if (closeHere) {
                chunks.add(buf.toString());
                buf.setLength(0);
                bufLen = 0;
            }
            if (bufLen > 0) {
                buf.append(" ");
            }
            buf.append(s);
            bufLen += s.length() + 1;
        }
        if (buf.length() > 0) {
            chunks.add(buf.toString());
        }
        return chunks;
    }

    /** 按中文/英文句末标点切句（保留标点本身） */
    private List<String> splitSentences(String text) {
        return Arrays.stream(text.split("(?<=[。！？!?；;\\n])"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    private List<String> hardSplitIfNeeded(String text) {
        if (text.length() <= maxChunkSize) {
            return List.of(text);
        }
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxChunkSize, text.length());
            chunks.add(text.substring(start, end).trim());
            start = end;
        }
        return chunks;
    }

    /** 余弦相似度（维度需一致） */
    private double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
