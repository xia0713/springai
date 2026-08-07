package com.example.springai.controller;

import com.example.springai.dto.KnowledgeRequest;
import com.example.springai.dto.SearchResult;
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
     * 写入单条知识
     * POST /api/knowledge
     * Body: { "content": "Spring AI 教程", "category": "tech", "source": "blog" }
     */
    @PostMapping
    public Map<String, Object> add(@RequestBody KnowledgeRequest request) {
        String id = knowledgeService.addKnowledge(request);
        return Map.of("id", id, "message", "写入成功");
    }

    /**
     * 批量写入知识
     * POST /api/knowledge/batch
     * Body: [ { "content": "...", "category": "tech" }, ... ]
     */
    @PostMapping("/batch")
    public Map<String, Object> batchAdd(@RequestBody List<KnowledgeRequest> requests) {
        List<String> ids = knowledgeService.batchAddKnowledge(requests);
        return Map.of("ids", ids, "count", ids.size());
    }

    /**
     * 相似度搜索
     * GET /api/knowledge/search?query=Spring+AI&topK=3&threshold=0.5
     */
    @GetMapping("/search")
    public List<SearchResult> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "3") int topK,
            @RequestParam(defaultValue = "0.5") double threshold) {
        return knowledgeService.search(query, topK, threshold);
    }

    /**
     * 按分类过滤搜索
     * GET /api/knowledge/search/category?query=向量&category=tech&topK=3
     */
    @GetMapping("/search/category")
    public List<SearchResult> searchByCategory(
            @RequestParam String query,
            @RequestParam String category,
            @RequestParam(defaultValue = "3") int topK) {
        return knowledgeService.searchByCategory(query, category, topK);
    }

    /**
     * 删除知识
     * DELETE /api/knowledge/{id}
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        knowledgeService.deleteKnowledge(id);
        return Map.of("message", "删除成功");
    }
}
