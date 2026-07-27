package com.example.springai.controller;


import com.example.springai.service.KnowledgeService;
import org.springframework.web.bind.annotation.*;

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
}
