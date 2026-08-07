package com.example.springai.service;

import com.example.springai.dto.KnowledgeRequest;
import com.example.springai.dto.SearchResult;
import com.example.springai.exception.AiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final VectorStore vectorStore;

    private final PgVectorStore pgVectorStore;

    /**
     * 构造带通用元数据的文档
     */
    private Document buildDocument(String content, String category, String source) {
        return new Document(
                content,
                Map.of(
                        "category", category != null ? category : "general",
                        "source", source != null ? source : "unknown",
                        "createTime", System.currentTimeMillis()
                )
        );
    }

    /**
     * 把向量库的 Document 转成对外返回的 SearchResult
     */
    private SearchResult toSearchResult(Document doc) {
        SearchResult result = new SearchResult();
        result.setId(doc.getId());
        result.setContent(doc.getText());
        result.setMetadata(doc.getMetadata());
        // distance 在 metadata 里（Spring AI 自动计算）
        Object distance = doc.getMetadata().get("distance");
        result.setDistance(distance != null ? (double) distance : -1);
        return result;
    }

    /**
     * 统一构造搜索请求（无过滤条件）
     */
    private SearchRequest buildSearchRequest(String query, int topK, double threshold) {
        return SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(threshold)
                .build();
    }

    /**
     * 统一构造搜索请求（带 DSL 过滤条件）
     */
    private SearchRequest buildSearchRequest(String query, int topK, double threshold, Filter.Expression filterExpression) {
        return SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(threshold)
                .filterExpression(filterExpression)
                .build();
    }

    /**
     * 写入单条知识
     */
    public String addKnowledge(KnowledgeRequest request) {
        try {
            Document doc = buildDocument(
                    request.getContent(),
                    request.getCategory(),
                    request.getSource()
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
                    .map(req -> buildDocument(req.getContent(), req.getCategory(), req.getSource()))
                    .toList();

            vectorStore.add(docs);

            List<String> ids = docs.stream().map(Document::getId).toList();
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
                    buildSearchRequest(query, topK, similarityThreshold, null)
            );

            List<SearchResult> results = docs.stream()
                    .map(this::toSearchResult)
                    .toList();

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
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            Filter.Expression filter = b.eq("category", category).build();

            List<Document> docs = vectorStore.similaritySearch(
                    buildSearchRequest(query, topK, 0.5, filter)
            );

            return docs.stream()
                    .map(this::toSearchResult)
                    .toList();

        } catch (Exception e) {
            log.error("分类搜索失败", e);
            throw new AiException("分类搜索失败: " + e.getMessage());
        }
    }

    /**
     * 使用 DSL 构建复杂过滤条件（示例：多分类 + 时间戳过滤）
     */
    public List<SearchResult> searchWithComplexFilter(String query, int topK) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        Filter.Expression filter = b.and(
                b.in("category", "framework", "database", "technique"),
                b.gte("createTime", 1700000000000L)  // 时间戳过滤
        ).build();

        return vectorStore.similaritySearch(
                        buildSearchRequest(query, topK, 0.7, filter)
                ).stream()
                .map(this::toSearchResult)
                .toList();
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
