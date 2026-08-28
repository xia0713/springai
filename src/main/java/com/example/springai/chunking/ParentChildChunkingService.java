package com.example.springai.chunking;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 父子文档分块：
 * <ul>
 *   <li>父块：大块，保存完整上下文（不向量化，或单独存储）</li>
 *   <li>子块：小块，向量化后用于检索，metadata 记录 parent_id + 父块全文</li>
 * </ul>
 * 检索时命中子块，反查父块全文作为上下文，兼顾"精准命中"与"上下文完整"。
 */
@Service
public class ParentChildChunkingService {

    /** 父块切分器（大块，2000 token） */
    private final TokenTextSplitter parentSplitter = TokenTextSplitter.builder()
            .withChunkSize(2000)
            .withMinChunkSizeChars(900)
            .withKeepSeparator(true)
            .build();

    /** 子块切分器（小块，400 token，用于检索） */
    private final TokenTextSplitter childSplitter = TokenTextSplitter.builder()
            .withChunkSize(400)
            .withMinChunkSizeChars(180)
            .withKeepSeparator(true)
            .build();

    public ParentChildChunks chunk(List<Document> docs, String source) {
        // ① 先切父块（大块）
        List<Document> parents = parentSplitter.apply(docs);

        // ② 每个父块再切子块（小块），子块记录 parent_id + 父块全文
        List<Document> children = new ArrayList<>();
        for (Document parent : parents) {
            String parentId = UUID.randomUUID().toString();
            List<Document> subChunks = childSplitter.apply(List.of(parent));
            for (Document child : subChunks) {
                child.getMetadata().put("parent_id", parentId);
                child.getMetadata().put("parent_content", parent.getText());
                child.getMetadata().put("source", source);
                children.add(child);
            }
        }
        return new ParentChildChunks(parents, children);
    }

    /** 父子块结果：父块列表 + 子块列表 */
    public record ParentChildChunks(List<Document> parents, List<Document> children) {
    }
}
