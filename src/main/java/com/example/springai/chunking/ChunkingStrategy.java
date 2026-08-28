package com.example.springai.chunking;

/**
 * 分块策略枚举 —— 5 种常用 RAG 分块方式
 */
public enum ChunkingStrategy {

    /** 固定大小：按固定字符数硬切，可带重叠，不感知语义/结构 */
    FIXED_SIZE("固定大小", "按固定字符数切分，支持重叠，不感知语义与结构"),

    /** 递归字符：按分隔符优先级递归切分（段落 → 换行 → 句末标点 → 逗号 → 硬切） */
    RECURSIVE_CHARACTER("递归字符", "按分隔符优先级递归切分，尽量在自然边界断开"),

    /** 语义分块：按句子 embedding 相似度找语义断点 */
    SEMANTIC("语义分块", "按句子向量相似度聚类，语义相近的句子合成一块"),

    /** 结构感知：按文档结构（Markdown 标题 / 段落）切分 */
    STRUCTURE_AWARE("结构感知", "按 Markdown 标题/段落结构切分，标题跟随内容"),

    /** 父子文档：大块(父)存上下文 + 小块(子)向量化检索 */
    PARENT_CHILD("父子文档", "父块存完整上下文，子块向量化检索并记录 parent_id");

    private final String label;
    private final String description;

    ChunkingStrategy(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }
}
