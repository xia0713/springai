package com.example.springai.chunking;

import com.example.springai.service.ConfigurableRagService;
import com.example.springai.service.HybridSearchService;
import com.example.springai.service.MultiRecallRagService;
import com.example.springai.service.QueryRewritingService;
import com.example.springai.service.RerankingService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * RAG 优化「单变量对比」功能测试（Day 24 单变量原则）。
 * <p>
 * 逐项加优化，记录每个优化对 Recall@5 的贡献：
 * V0 纯向量 → V1 混合检索 → V2 重排序 → V3 查询改写 → V4 多路召回整合。
 * <p>
 * 只测「召回」环节（不含 LLM 生成），用 {@code expectedDoc} 是否进入 top5 判定命中，
 * 避免为 40 条查询反复调用大模型生成、省 token 且结果确定。
 * <p>
 * 依赖真实 pgvector + embedding（重排无 Cohere Key 时走 embedding 本地重排）。
 */
@SpringBootTest
class ChunkTest {

    @Autowired VectorStore vectorStore;
    @Autowired HybridSearchService hybridSearch;
    @Autowired QueryRewritingService rewriter;
    @Autowired RerankingService reranker;
    @Autowired ChatClient.Builder chatClientBuilder;
    @Autowired MultiRecallRagService multiRecall;

    // ============ 测试用例定义 ============

    record TestCase(String question, String expectedDoc, TestType type) {}

    enum TestType { SEMANTIC, EXACT, EDGE }

    /** 40 条有答案查询。expectedDoc 已与 vector_store 实际 source 对齐。 */
    static final List<TestCase> TEST_SET = List.of(
            // ===== 语义查询（15）：同义词、改写、口语化 =====
            new TestCase("退货需要什么条件？", "退货退款政策.pdf", TestType.SEMANTIC),
            new TestCase("怎么申请退款？", "退货退款政策.pdf", TestType.SEMANTIC),
            new TestCase("钱什么时候能退回来？", "退货退款政策.pdf", TestType.SEMANTIC),
            new TestCase("退货邮费谁出？", "退货退款政策.pdf", TestType.SEMANTIC),
            new TestCase("退货运费怎么算？", "退货退款政策.pdf", TestType.SEMANTIC),
            new TestCase("上班时间是几点？", "公司员工手册.pdf", TestType.SEMANTIC),
            new TestCase("一年有多少天年假？", "公司员工手册.pdf", TestType.SEMANTIC),
            new TestCase("报销怎么走流程？", "公司员工手册.pdf", TestType.SEMANTIC),
            new TestCase("耳机充满电能听多久？", "蓝牙耳机 X1 产品说明.md", TestType.SEMANTIC),
            new TestCase("手表防不防水？", "蓝牙耳机 X1 产品说明.md", TestType.SEMANTIC),
            new TestCase("怎么升级成会员？", "电商平台会员政策.pfd", TestType.SEMANTIC),
            new TestCase("积分能干嘛？", "电商平台会员政策.pfd", TestType.SEMANTIC),
            new TestCase("员工迟到会怎么样？", "公司员工手册.pdf", TestType.SEMANTIC),
            new TestCase("生病请假要什么手续？", "公司员工手册.pdf", TestType.SEMANTIC),
            new TestCase("买的东西不满意想退，有什么要求？", "退货退款政策.pdf", TestType.SEMANTIC),

            // ===== 精确查询（15）：订单号、错误码、型号、数字 =====
            new TestCase("订单号 ORD-142 的物流状态？", "物流记录.xlsx", TestType.EXACT),
            new TestCase("订单号 ORD-144 什么时候发货？", "物流记录.xlsx", TestType.EXACT),
            new TestCase("错误码 503 什么意思？", "系统错误码.pdf", TestType.EXACT),
            new TestCase("错误码 403 是什么问题？", "系统错误码.pdf", TestType.EXACT),
            new TestCase("错误码 502 是什么问题？", "系统错误码.pdf", TestType.EXACT),
            new TestCase("错误码 401 怎么处理？", "系统错误码.pdf", TestType.EXACT),
            new TestCase("蓝牙耳机 X1 防水等级？", "蓝牙耳机 X1 产品说明.md", TestType.EXACT),
            new TestCase("蓝牙耳机 X1 蓝牙版本？", "蓝牙耳机 X1 产品说明.md", TestType.EXACT),
            new TestCase("智能手表 S2 续航多久？", "蓝牙耳机 X1 产品说明.md", TestType.EXACT),
            new TestCase("智能手表 S2 屏幕多大？", "蓝牙耳机 X1 产品说明.md", TestType.EXACT),
            new TestCase("耳机 X1 单只多重？", "蓝牙耳机 X1 产品说明.md", TestType.EXACT),
            new TestCase("订单号 ORD-145 到了吗？", "物流记录.xlsx", TestType.EXACT),
            new TestCase("订单号 ORD-143 什么时候到？", "物流记录.xlsx", TestType.EXACT),
            new TestCase("错误码 1001 是什么？", "系统错误码.pdf", TestType.EXACT),
            new TestCase("运动鞋 R3 有什么尺码？", "蓝牙耳机 X1 产品说明.md", TestType.EXACT),

            // ===== 边界查询（10）：模糊、多跳、跨文档、条件判断 =====
            new TestCase("买了耳机发现有问题，能退吗？", "退货退款政策.pdf", TestType.EDGE),
            new TestCase("退货后钱多久到账，用支付宝的话？", "退货退款政策.pdf", TestType.EDGE),
            new TestCase("银卡和金卡会员折扣差多少？", "电商平台会员政策.pfd", TestType.EDGE),
            new TestCase("商品有质量问题，退货运费自理还是商家出？", "退货退款政策.pdf", TestType.EDGE),
            new TestCase("工作满一年能请几天不扣钱的假？", "公司员工手册.pdf", TestType.EDGE),
            new TestCase("报销什么时候能到账？", "公司员工手册.pdf", TestType.EDGE),
            new TestCase("想升到钻石会员要花多少钱？", "电商平台会员政策.pfd", TestType.EDGE),
            new TestCase("蓝牙耳机和智能手表哪个续航长？", "蓝牙耳机 X1 产品说明.md", TestType.EDGE),
            new TestCase("差旅报销要准备什么材料？", "公司员工手册.pdf", TestType.EDGE),
            new TestCase("哪些商品不能退货？", "退货退款政策.pdf", TestType.EDGE)
    );

    // ============ 版本定义（逐项加优化） ============

    record Version(String name, String config, Function<String, List<Document>> retriever) {}

    @Test
    void 逐项加优化对比Recall() {
        List<Version> versions = List.of(
                new Version("V0 基线", "纯向量检索",
                        new ConfigurableRagService(false, false, false,
                                vectorStore, hybridSearch, rewriter, reranker, chatClientBuilder)::retrieve),
                new Version("V1", "+ 混合检索(BM25)",
                        new ConfigurableRagService(true, false, false,
                                vectorStore, hybridSearch, rewriter, reranker, chatClientBuilder)::retrieve),
                new Version("V2", "+ 重排序",
                        new ConfigurableRagService(true, true, false,
                                vectorStore, hybridSearch, rewriter, reranker, chatClientBuilder)::retrieve),
                new Version("V3", "+ 查询改写",
                        new ConfigurableRagService(true, true, true,
                                vectorStore, hybridSearch, rewriter, reranker, chatClientBuilder)::retrieve),
                new Version("V4", "+ 多路召回整合", multiRecall::retrieve)
        );

        List<RecallResult> results = new ArrayList<>();
        for (Version v : versions) {
            results.add(evaluate(v, TEST_SET));
        }

        printSummary(results);
        printByType(results);
        printMissed(results);
    }

    // ============ 评估逻辑 ============

    record RecallResult(String version, double overall,
                        Map<TestType, Double> byType, List<String> missed, int errors) {}

    private RecallResult evaluate(Version v, List<TestCase> cases) {
        int hit = 0, errors = 0;
        Map<TestType, int[]> per = new EnumMap<>(TestType.class); // [hit, total]
        List<String> missed = new ArrayList<>();

        for (TestCase tc : cases) {
            int[] h = per.computeIfAbsent(tc.type(), k -> new int[2]);
            h[1]++;
            try {
                boolean found = v.retriever().apply(tc.question()).stream()
                        .map(ChunkTest::source)
                        .anyMatch(tc.expectedDoc()::equals);
                if (found) {
                    hit++;
                    h[0]++;
                } else {
                    missed.add(tc.question() + "  (期望 " + tc.expectedDoc() + ")");
                }
            } catch (Exception e) {
                errors++;
                missed.add(tc.question() + "  [异常] " + e.getClass().getSimpleName() + ": "
                        + e.getMessage());
            }
        }

        Map<TestType, Double> byType = new EnumMap<>(TestType.class);
        per.forEach((t, h) -> byType.put(t, h[1] == 0 ? 0.0 : h[0] * 100.0 / h[1]));

        return new RecallResult(v.name(), hit * 100.0 / cases.size(), byType, missed, errors);
    }

    private static String source(Document d) {
        return String.valueOf(d.getMetadata().getOrDefault("source", ""));
    }

    // ============ 输出 ============

    private void printSummary(List<RecallResult> results) {
        System.out.println();
        System.out.println("=".repeat(76));
        System.out.println("逐项加优化 · Recall@5 对比（共 " + TEST_SET.size() + " 条有答案查询）");
        System.out.println("=".repeat(76));
        System.out.printf("%-8s %-18s %10s %12s%n", "版本", "配置", "Recall@5", "相比上版");
        System.out.println("-".repeat(76));

        double prev = -1;
        for (RecallResult r : results) {
            String delta = prev < 0 ? "—" : String.format("%+.1fpp", r.overall() - prev);
            String err = r.errors() > 0 ? "   ⚠ " + r.errors() + " 条异常" : "";
            System.out.printf("%-8s %-18s %9.1f%% %12s%s%n",
                    r.version(), configOf(r.version()), r.overall(), delta, err);
            prev = r.overall();
        }
    }

    private void printByType(List<RecallResult> results) {
        System.out.println();
        System.out.println("按题型拆分 Recall@5：");
        System.out.printf("%-8s %10s %10s %10s%n", "版本", "语义(15)", "精确(15)", "边界(10)");
        System.out.println("-".repeat(76));
        for (RecallResult r : results) {
            System.out.printf("%-8s %9.0f%% %9.0f%% %9.0f%%%n",
                    r.version(),
                    r.byType().getOrDefault(TestType.SEMANTIC, 0.0),
                    r.byType().getOrDefault(TestType.EXACT, 0.0),
                    r.byType().getOrDefault(TestType.EDGE, 0.0));
        }
    }

    private void printMissed(List<RecallResult> results) {
        System.out.println();
        System.out.println("各版本未命中的查询（观察每个优化「修好」了哪些题）：");
        for (RecallResult r : results) {
            System.out.printf("  ■ %s（%d 条未命中）%n", r.version(), r.missed().size());
            for (String m : r.missed()) {
                System.out.println("      - " + m);
            }
        }
    }

    private final Map<String, String> configs = Map.of(
            "V0 基线", "纯向量检索",
            "V1", "+ 混合检索(BM25)",
            "V2", "+ 重排序",
            "V3", "+ 查询改写",
            "V4", "+ 多路召回整合");

    private String configOf(String version) {
        return configs.getOrDefault(version, "");
    }
}
