package com.example.springai.controller;


import com.example.springai.service.KnowledgeService;
import org.springframework.web.bind.annotation.*;

import javax.naming.directory.SearchResult;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    /**
     * 批量写入知识
     * POST /api/knowledge/add
     * Body: { "contents": ["文本1", "文本2"], "category": "tech" }
     */
    @PostMapping("/add")
    public Map<String, Object> addKnowledge(@RequestBody AddRequest request) {
        List<String> ids = knowledgeService.addKnowledge(request.contents(), request.category());
        return Map.of("success", true, "ids", ids, "count", ids.size());
    }

    /**
     * 相似度搜索
     * GET /api/knowledge/search?query=Spring+AI&topK=3
     */
    @GetMapping("/search")
    public List<Map<String, Object>> search(@RequestParam String query,
                                            @RequestParam(defaultValue = "3") int topK) {
        return knowledgeService.search(query, topK).stream()
                .map(doc -> Map.<String, Object>of(
                        "id", doc.getId(),
                        "content", doc.getText(),
                        "metadata", doc.getMetadata()
                ))
                .toList();
    }

    /**
     * 带分类过滤的搜索
     * GET /api/knowledge/search-filter?query=向量&topK=3&category=database
     */
    @GetMapping("/search-filter")
    public List<Map<String, Object>> searchWithFilter(@RequestParam String query,
                                                      @RequestParam(defaultValue = "3") int topK,
                                                      @RequestParam String category) {
        return knowledgeService.searchWithFilter(query, topK, category).stream()
                .map(doc -> Map.<String, Object>of(
                        "id", doc.getId(),
                        "content", doc.getText(),
                        "metadata", doc.getMetadata()
                ))
                .toList();
    }

    /**
     * 按 ID 删除
     * DELETE /api/knowledge/{id}
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        knowledgeService.deleteByIds(List.of(id));
        return Map.of("success", true);
    }

    public record AddRequest(List<String> contents, String category) {}

    /**
     * 写入知识
     * POST /api/knowledge
     */
    @PostMapping
    public Map<String, String> add(@RequestBody KnowledgeRequest request) {
        String id = knowledgeService.addKnowledge(request);
        return Map.of("id", id, "message", "写入成功");
    }

    /**
     * 批量写入
     * POST /api/knowledge/batch
     */
    @PostMapping("/batch")
    public Map<String, Object> batchAdd(@RequestBody List<KnowledgeRequest> requests) {
        List<String> ids = knowledgeService.batchAddKnowledge(requests);
        return Map.of("ids", ids, "count", ids.size());
    }

    /**
     * 相似度搜索
     * GET /api/knowledge/search?query=你好&topK=3&threshold=0.5
     */
    @GetMapping("/search")
    public List<SearchResult> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "3") int topK,
            @RequestParam(defaultValue = "0.5") double threshold) {
        return knowledgeService.search(query, topK, threshold);
    }

    /**
     * 按分类搜索
     * GET /api/knowledge/search/category?query=你好&category=tech&topK=3
     */
    @GetMapping("/search/category")
    public List<SearchResult> searchByCategory(
            @RequestParam String query,
            @RequestParam String category,
            @RequestParam(defaultValue = "3") int topK) {
        return knowledgeService.searchByCategory(query, category, topK);
    }

    /**
     * 删除
     * DELETE /api/knowledge/{id}
     */
    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable String id) {
        knowledgeService.deleteKnowledge(id);
        return Map.of("message", "删除成功");
    }
}
