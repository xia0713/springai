package com.example.springai.chunking;

import java.util.List;
import java.util.Map;

/**
 * 分块结果（对外返回用，不含原始 Document，避免序列化冗余）
 *
 * @param strategy    使用的分块策略
 * @param chunkCount  最终块数（父子文档 = 子块数）
 * @param parentCount 父子文档时的父块数，其余策略为 0
 * @param totalChars  全部块的总字符数
 * @param chunks      每个块的摘要信息
 */
public record ChunkingResult(
        ChunkingStrategy strategy,
        int chunkCount,
        int parentCount,
        int totalChars,
        List<ChunkView> chunks) {

    /**
     * 单个块的展示信息
     *
     * @param id        块 ID
     * @param charCount 字符数
     * @param preview   文本预览（前 100 字）
     * @param metadata  元数据（来源、parent_id 等）
     */
    public record ChunkView(String id, int charCount, String preview, Map<String, Object> metadata) {
    }
}
