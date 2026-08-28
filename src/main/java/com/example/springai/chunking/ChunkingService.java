package com.example.springai.chunking;

import com.example.springai.config.TextCleanTransformer;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 分块门面：解析 → 清洗 → 按策略分块 →（可选）入库。
 * <p>
 * 统一对外暴露 5 种分块策略，屏蔽各策略实现的差异。
 */
@Service
public class ChunkingService {

    private final TextCleanTransformer textCleanTransformer;
    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;
    private final ParentChildChunkingService parentChildChunkingService;

    public ChunkingService(TextCleanTransformer textCleanTransformer,
                           EmbeddingModel embeddingModel,
                           VectorStore vectorStore,
                           ParentChildChunkingService parentChildChunkingService) {
        this.textCleanTransformer = textCleanTransformer;
        this.embeddingModel = embeddingModel;
        this.vectorStore = vectorStore;
        this.parentChildChunkingService = parentChildChunkingService;
    }

    /**
     * 上传文件按策略分块
     *
     * @param file     上传的文件
     * @param strategy 分块策略
     * @param store    是否同时写入向量库
     */
    public ChunkingResult chunk(MultipartFile file, ChunkingStrategy strategy, boolean store) throws IOException {
        TikaDocumentReader reader = new TikaDocumentReader(file.getResource());
        List<Document> docs = textCleanTransformer.apply(reader.read());
        String source = file.getOriginalFilename();
        return chunkAndOptionallyStore(docs, strategy, source, store);
    }

    /**
     * 纯文本按策略分块
     *
     * @param text     原始文本
     * @param strategy 分块策略
     * @param store    是否同时写入向量库
     */
    public ChunkingResult chunk(String text, ChunkingStrategy strategy, boolean store) {
        return chunkAndOptionallyStore(List.of(new Document(text)), strategy, "text", store);
    }

    private ChunkingResult chunkAndOptionallyStore(List<Document> docs, ChunkingStrategy strategy,
                                                   String source, boolean store) {
        Split split = split(docs, strategy, source);
        if (store && !split.toStore().isEmpty()) {
            vectorStore.add(split.toStore());
        }
        return toResult(strategy, split);
    }

    private Split split(List<Document> docs, ChunkingStrategy strategy, String source) {
        if (strategy == ChunkingStrategy.PARENT_CHILD) {
            ParentChildChunkingService.ParentChildChunks pcc = parentChildChunkingService.chunk(docs, source);
            return new Split(pcc.children(), pcc.parents().size());
        }

        List<Document> chunks = switch (strategy) {
            case FIXED_SIZE -> new FixedSizeTextSplitter(500, 50).apply(docs);
            case RECURSIVE_CHARACTER -> new RecursiveCharacterTextSplitter(500, 50).apply(docs);
            case STRUCTURE_AWARE -> new StructureAwareSplitter(800).apply(docs);
            case SEMANTIC -> new SemanticChunkingSplitter(embeddingModel, 512, 0.5).apply(docs);
            case PARENT_CHILD -> throw new IllegalStateException("PARENT_CHILD 已在上方处理，不可达");
        };

        // 补充来源元数据（溯源用）
        chunks = chunks.stream()
                .peek(d -> d.getMetadata().putIfAbsent("source", source))
                .toList();
        return new Split(chunks, 0);
    }

    private ChunkingResult toResult(ChunkingStrategy strategy, Split split) {
        List<ChunkingResult.ChunkView> views = split.toStore().stream()
                .map(d -> new ChunkingResult.ChunkView(
                        d.getId(),
                        d.getText().length(),
                        preview(d.getText()),
                        d.getMetadata()))
                .toList();
        int totalChars = split.toStore().stream().mapToInt(d -> d.getText().length()).sum();
        return new ChunkingResult(strategy, split.toStore().size(), split.parentCount(), totalChars, views);
    }

    /** 文本预览：压成单行，截前 100 字 */
    private String preview(String text) {
        String oneLine = text.replaceAll("\\s+", " ");
        return oneLine.length() > 100 ? oneLine.substring(0, 100) + "…" : oneLine;
    }

    /** 内部中间结果：待入库的原始 Document + 父块数 */
    private record Split(List<Document> toStore, int parentCount) {
    }
}
