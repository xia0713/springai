package com.example.springai.evaluation;

import com.example.springai.chunking.RagTestCorpus;
import com.example.springai.service.MultiRecallRagService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;

/**
 * RAG 生成环节评估（Day47/49）。
 * <p>
 * 补 {@link com.example.springai.chunking.ChunkTest}（只测召回）的另一半：
 * 测「答案准不准 + 幻觉高不高」。核心是让模型可判——有答案的题看关键词，
 * 资料外的题看它是否【拒答】而非编造。
 * <p>
 * 依赖真实 pgvector + embedding + LLM（生成要花钱，故用少量用例）。
 */
@SpringBootTest(properties = {
        "logging.level.org.springframework.ai=INFO",
        "logging.level.reactor.netty.http.client=WARN",
        "logging.level.org.apache.hc.client5=WARN"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RagGenerationEval {

    @Autowired VectorStore vectorStore;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MultiRecallRagService multiRecall;

    /** 生成评估用例：hasAnswer=true 测准确率，false 测拒答/幻觉 */
    record GenCase(String question, String[] mustContain, boolean hasAnswer) {}

    static final List<GenCase> CASES = List.of(
            // ===== 有答案（5 道）：答案应包含这些关键词 =====
            new GenCase("蓝牙耳机 X1 续航多久？", new String[]{"8", "小时"}, true),
            new GenCase("退货邮费谁出？", new String[]{"质量问题", "商家"}, true),
            new GenCase("工作时间是几点到几点？", new String[]{"9:00", "18:00"}, true),
            new GenCase("工作满一年有几天年假？", new String[]{"5"}, true),
            new GenCase("怎么申请加入金卡会员？", new String[]{"5000"}, true),
            // ===== 资料外（2 道）：应拒答，不应编造 =====
            new GenCase("今天股票大盘多少点？", new String[]{}, false),
            new GenCase("怎么预约 NBA 球赛门票？", new String[]{}, false)
    );

    @BeforeAll
    void seedCorpus() {
        if (!RagTestCorpus.isSeeded(jdbcTemplate)) {
            int n = RagTestCorpus.seed(vectorStore, jdbcTemplate);
            System.out.println("已灌入细粒度语料 " + n + " 条 chunk");
        } else {
            System.out.println("语料已存在，跳过灌库");
        }
    }

    @Test
    void 生成质量评估() {
        int correct = 0, rejected = 0, hallucinated = 0;
        int hasAnswerCount = 0, noAnswerCount = 0;

        for (GenCase c : CASES) {
            String answer = multiRecall.answer(c.question());
            System.out.println("\n问题: " + c.question());
            System.out.println("答案: " + answer);

            if (c.hasAnswer()) {
                hasAnswerCount++;
                boolean ok = Arrays.stream(c.mustContain()).allMatch(answer::contains);
                if (ok) {
                    correct++;
                } else {
                    System.out.println("  ❌ 答案不准，缺关键词: " + Arrays.toString(c.mustContain()));
                }
            } else {
                noAnswerCount++;
                boolean rejectedOk = answer.contains("抱歉") || answer.contains("未收录")
                        || answer.contains("无法回答") || answer.contains("暂未");
                if (rejectedOk) {
                    rejected++;
                    System.out.println("  ✅ 正确拒答");
                } else {
                    hallucinated++;
                    System.out.println("  ❌ 资料外问题未拒答，疑似幻觉");
                }
            }
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("RAG 生成质量评估（共 " + CASES.size() + " 题）");
        System.out.printf("准确率 = %.0f%%（%d/%d 有答案题答对）%n",
                hasAnswerCount == 0 ? 0 : correct * 100.0 / hasAnswerCount, correct, hasAnswerCount);
        System.out.printf("拒答率 = %.0f%%（%d/%d 资料外题正确拒答）%n",
                noAnswerCount == 0 ? 0 : rejected * 100.0 / noAnswerCount, rejected, noAnswerCount);
        System.out.printf("幻觉率 = %.0f%%（%d/%d 资料外题编造）%n",
                noAnswerCount == 0 ? 0 : hallucinated * 100.0 / noAnswerCount, hallucinated, noAnswerCount);
        System.out.println("=".repeat(60));
    }
}
