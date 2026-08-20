package com.example.springai;

import com.example.springai.dto.CodeReviewResult;
import com.example.springai.service.CodeReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class SpringAiApplicationTests {

    @Autowired
    private CodeReviewService service;

    @Test
    void 检测SQL注入漏洞() {
        String code = "String sql = \"SELECT * FROM users WHERE name = '\" + userInput + \"'\";";
        CodeReviewResult r = service.review(code);
        System.out.println("=== SQL注入检测 ===");
        System.out.println("Verdict: " + r.verdict());
        System.out.println("Issues:");
        r.issues().forEach(i -> {
            System.out.println("  - Category: " + i.category());
            System.out.println("    Severity: " + i.severity());
            System.out.println("    Description: " + i.description());
        });
        // 更灵活的检查：只要检测到 SECURITY 相关的问题即可
        boolean hasSecurityIssue = r.issues().stream().anyMatch(i ->
                i.category() != null && (
                        i.category().contains("SECURITY") ||
                        i.category().contains("SQL") ||
                        i.description() != null && (
                                i.description().contains("SQL") ||
                                i.description().contains("注入") ||
                                i.description().contains("injection")
                        )
                )
        );
        assertTrue(hasSecurityIssue, "未检出 SQL 注入相关安全问题，实际返回：" + r.issues());
    }

    @Test
    void 检测空指针风险() {
        String code = "public void p(Order o) { String n = o.getUser().getName(); }";
        CodeReviewResult r = service.review(code);
        System.out.println("=== 空指针检测 ===");
        System.out.println("Verdict: " + r.verdict());
        System.out.println("Issues:");
        r.issues().forEach(i -> {
            System.out.println("  - Category: " + i.category());
            System.out.println("    Severity: " + i.severity());
            System.out.println("    Description: " + i.description());
        });
        assertTrue(r.issues().stream().anyMatch(i ->
                i.category() != null && (
                        "NULL_POINTER".equals(i.category()) ||
                        i.category().contains("空指针") ||
                        i.category().contains("NPE")
                )
        ), "未检出空指针风险");
    }

    @Test
    void 正常代码不误报() {
        String code = "public int add(int a, int b) { return a + b; }";
        CodeReviewResult r = service.review(code);
        assertEquals("PASS", r.verdict(), "正常代码被误报");
    }
}
