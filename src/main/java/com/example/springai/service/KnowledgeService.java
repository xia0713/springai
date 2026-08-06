package com.example.springai.service;

import com.example.springai.exception.AiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.naming.directory.SearchResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final VectorStore vectorStore;

    private final PgVectorStore pgVectorStore;

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


    /**
     * 写入知识
     */
    public String addKnowledge(KnowledgeRequest request) {
        try {
            Document doc = new Document(
                    request.getContent(),
                    Map.of(
                            "category", request.getCategory() != null ? request.getCategory() : "general",
                            "source", request.getSource() != null ? request.getSource() : "unknown",
                            "createTime", System.currentTimeMillis()
                    )
            );

            vectorStore.add(List.of(doc));

            log.info("知识写入成功 id={}, content长度={}", doc.getId(), request.getContent().length());
            return doc.getId();

        } catch (Exception e) {
            log.error("知识写入失败", e);
            throw new AiException("知识写入失败: " + e.getMessage());
        }
    }

    /**
     * 批量写入
     */
    public List<String> batchAddKnowledge(List<KnowledgeRequest> requests) {
        try {
            List<Document> docs = requests.stream()
                    .map(req -> new Document(
                            req.getContent(),
                            Map.of(
                                    "category", req.getCategory() != null ? req.getCategory() : "general",
                                    "source", req.getSource() != null ? req.getSource() : "unknown",
                                    "createTime", System.currentTimeMillis()
                            )
                    ))
                    .collect(Collectors.toList());

            vectorStore.add(docs);

            List<String> ids = docs.stream().map(Document::getId).collect(Collectors.toList());
            log.info("批量写入成功 count={}", ids.size());
            return ids;

        } catch (Exception e) {
            log.error("批量写入失败", e);
            throw new AiException("批量写入失败: " + e.getMessage());
        }
    }

    /**
     * 相似度搜索
     */
    public List<SearchResult> search(String query, int topK, double similarityThreshold) {
        try {
            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(topK)
                            .similarityThreshold(similarityThreshold)
                            .build()
            );

            List<SearchResult> results = docs.stream()
                    .map(doc -> {
                        SearchResult result = new SearchResult();
                        result.setId(doc.getId());
                        result.setContent(doc.getContent());
                        result.setMetadata(doc.getMetadata());
                        // distance 在 metadata 里（Spring AI 自动计算）
                        Object distance = doc.getMetadata().get("distance");
                        result.setDistance(distance != null ? (double) distance : -1);
                        return result;
                    })
                    .collect(Collectors.toList());

            log.info("搜索完成 query={}, 命中{}条", query, results.size());
            return results;

        } catch (Exception e) {
            log.error("搜索失败", e);
            throw new AiException("搜索失败: " + e.getMessage());
        }
    }

    /**
     * 按分类过滤搜索
     */
    public List<SearchResult> searchByCategory(String query, String category, int topK) {
        try {
            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(topK)
                            .similarityThreshold(0.5)
                            .filterExpression("category == '" + category + "'")
                            .build()
            );

            return docs.stream()
                    .map(doc -> {
                        SearchResult result = new SearchResult();
                        result.setId(doc.getId());
                        result.setContent(doc.getContent());
                        result.setMetadata(doc.getMetadata());
                        Object distance = doc.getMetadata().get("distance");
                        result.setDistance(distance != null ? (double) distance : -1);
                        return result;
                    })
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("分类搜索失败", e);
            throw new AiException("分类搜索失败: " + e.getMessage());
        }
    }

    /**
     * 删除知识
     */
    public void deleteKnowledge(String docId) {
        try {
            vectorStore.delete(List.of(docId));
            log.info("删除成功 id={}", docId);
        } catch (Exception e) {
            log.error("删除失败", e);
            throw new AiException("删除失败: " + e.getMessage());
        }
    }
}
