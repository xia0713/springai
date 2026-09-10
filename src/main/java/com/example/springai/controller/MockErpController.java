package com.example.springai.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 模拟公司 ERP 员工接口（Day53 被对接的"现有业务 API"）。
 * <p>
 * 特意包含真实企业的三个特征：
 * ① 鉴权：X-API-KEY 请求头校验
 * ② 分页：返回体含 total/page/records
 * ③ 延迟开关：?delayMs=8000 模拟慢接口（测超时用）
 */
@RestController
@RequestMapping("/api/mock-erp")
public class MockErpController {

    private static final String VALID_KEY = "demo-key-123";

    /** 模拟 23 个员工的大结果（含无关大字段 address，模拟臃肿返回） */
    private static List<Map<String, Object>> employees() {
        String[] depts = {"研发部", "销售部", "财务部"};
        return IntStream.rangeClosed(1, 23)
                .mapToObj(i -> Map.<String, Object>of(
                        "id", i,
                        "empNo", "E%03d".formatted(i),
                        "name", "员工" + i,
                        "department", depts[i % 3],
                        "title", i % 2 == 0 ? "高级工程师" : "工程师",
                        "phone", "138%08d".formatted(i),
                        "email", "emp" + i + "@company.com",
                        "address", "某某市某某区某某路" + i + "号"
                ))
                .toList();
    }

    @GetMapping("/employees")
    public Map<String, Object> list(
            @RequestHeader(value = "X-API-KEY", required = false) String apiKey,
            @RequestParam(required = false) String department,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "0") long delayMs) throws InterruptedException {

        // ① 鉴权：key 不对直接 401 语义
        if (!VALID_KEY.equals(apiKey)) {
            return Map.of("code", 401, "message", "API Key 无效");
        }
        // ② 延迟开关：模拟慢接口
        if (delayMs > 0) {
            Thread.sleep(delayMs);
        }
        // ③ 过滤 + 分页
        List<Map<String, Object>> filtered = employees().stream()
                .filter(e -> department == null || department.isBlank()
                        || department.equals(e.get("department")))
                .toList();
        List<Map<String, Object>> records = filtered.stream()
                .skip((long) (page - 1) * size)
                .limit(size)
                .toList();
        return Map.of(
                "code", 0,
                "message", "success",
                "data", Map.of(
                        "total", filtered.size(),
                        "page", page,
                        "records", records
                )
        );
    }
}
