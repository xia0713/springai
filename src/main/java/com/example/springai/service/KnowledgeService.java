package com.example.springai.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class KnowledgeService {

    private final VectorStore vectorStore;

    private final PgVectorStore pgVectorStore;
    public KnowledgeService(VectorStore vectorStore, PgVectorStore pgVectorStore) {
        this.vectorStore = vectorStore;
        this.pgVectorStore = pgVectorStore;
    }

    /**
     * 批量写入知识文档
     */
    public List<String> addKnowledge(List<String> contents, String category) {
        List<Document> documents = contents.stream()
                .map(content -> new Document(
                        content,
                        Map.of(
                                "category", category,
                                "createTime", System.currentTimeMillis()
                        )
                ))
                .toList();

        vectorStore.add(documents);

        // 返回写入的文档 ID 列表
        return documents.stream()
                .map(Document::getId)
                .toList();
    }

    /**
     * 写入单条带元数据的文档
     */
    public String addDocument(String content, Map<String, Object> metadata) {
        Document doc = new Document(content, metadata);
        vectorStore.add(List.of(doc));
        return doc.getId();
    }

    /**
     * 按 ID 删除
     */
    public boolean deleteByIds(List<String> ids) {
        vectorStore.delete(ids);
        return true;
    }

    /**
     * 按分类删除
     */
    public boolean deleteByCategory(String category) {
        vectorStore.delete("category == '" + category + "'");
        return true;
    }

    /**
     * 基础相似度搜索
     */
    public List<Document> search(String query, int topK) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(0.5)  // 过滤掉不相关的结果
                .build();

        return vectorStore.similaritySearch(request);
    }

    /**
     * 带元数据过滤的搜索
     */
    public List<Document> searchWithFilter(String query, int topK, String category) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(0.5)
                .filterExpression("category == '" + category + "'")
                .build();

        return vectorStore.similaritySearch(request);
    }

    /**
     * 使用 DSL 构建复杂过滤条件
     */
    public List<Document> searchWithComplexFilter(String query, int topK) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(0.7)
                .filterExpression(
                        b.and(
                                b.in("category", "framework", "database", "technique"),
                                b.gte("createTime", 1700000000000L)  // 时间戳过滤
                        ).build()
                )
                .build();

        return vectorStore.similaritySearch(request);
    }


    /**
     * 直接用 SQL 查询向量数据
     * 当 VectorStore 接口不够用时，可以直接拿到 JdbcTemplate 执行原生 SQL
     */
    public void rawQuery() {
        Optional<JdbcTemplate> nativeClient = pgVectorStore.getNativeClient();

        if (nativeClient.isPresent()) {
            JdbcTemplate jdbc = nativeClient.get();

            // 查看当前表里有多少条数据
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM vector_store",
                    Integer.class
            );
            System.out.println("向量总数: " + count);

            // 查看表的元数据信息
            jdbc.queryForList(
                    "SELECT id, LEFT(content, 50) as content_preview, metadata FROM vector_store LIMIT 5"
            ).forEach(row -> System.out.println(row));
        }
    }
}
