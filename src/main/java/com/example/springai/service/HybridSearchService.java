package com.example.springai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索：**pgvector 做向量，tsvector 做全文检索**，一条 SQL 融合
 */
@Service
public class HybridSearchService {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;

    private static final String HYBRID_SQL = """
        WITH semantic_search AS (
            SELECT id, content, metadata,
                   RANK() OVER (ORDER BY embedding <=> ?::vector) as rank_ix
            FROM vector_store
            ORDER BY embedding <=> ?::vector
            LIMIT 50
        ),
        keyword_search AS (
            SELECT id, content, metadata,
                   RANK() OVER (ORDER BY ts_rank_cd(
                       to_tsvector('simple', content),
                       plainto_tsquery('simple', ?)) DESC) as rank_ix
            FROM vector_store
            WHERE to_tsvector('simple', content) @@ plainto_tsquery('simple', ?)
            LIMIT 50
        )
        SELECT COALESCE(s.id, k.id) as id,
               COALESCE(s.content, k.content) as content,
               COALESCE(s.metadata, k.metadata) as metadata,
               (COALESCE(1.0/(60+s.rank_ix), 0.0) + COALESCE(1.0/(60+k.rank_ix), 0.0)) as rrf_score
        FROM semantic_search s
        FULL OUTER JOIN keyword_search k ON s.id = k.id
        ORDER BY rrf_score DESC
        LIMIT ?
        """;

    public HybridSearchService(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModel = embeddingModel;
    }

    public List<Document> hybridSearch(String query) {
        return hybridSearch(query, 20);
    }

    public List<Document> hybridSearch(String query,int count) {
        // 1. 把查询转成向量
        float[] embedding = embeddingModel.embed(query);

        // 2. 执行混合检索 SQL（向量参数传两次，查询文本传两次）
        return jdbcTemplate.query(HYBRID_SQL, (rs, rowNum) -> {
            String content = rs.getString("content");
            Map<String, Object> metadata = parseMetadata(rs.getString("metadata"));
            metadata.put("rrf_score", rs.getDouble("rrf_score"));
            return new Document(rs.getString("id"), content, metadata);
        }, embedding, embedding, query, query,count);
    }

    private Map<String, Object> parseMetadata(String json) {
        // 用 Jackson 解析 metadata JSON（简化处理）
        try {
            return new ObjectMapper().readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }
}
