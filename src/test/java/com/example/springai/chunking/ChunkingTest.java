package com.example.springai.chunking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * 5 种分块方式的功能测试。
 * <p>
 * 纯离线、确定性：固定大小/递归字符/结构感知/父子文档不依赖外部服务，
 * 语义分块用 Mock 的 EmbeddingModel 返回可控向量，验证语义断点逻辑。
 */
class ChunkingTest {

    // ==================== 1. 固定大小 ====================

    @Test
    @DisplayName("固定大小：按固定字符数硬切")
    void 固定大小按字符数切分() {
        String text = "0123456789ABCDEFGHIJ";   // 20 字符
        FixedSizeTextSplitter splitter = new FixedSizeTextSplitter(8, 0);

        List<String> chunks = texts(splitter.apply(List.of(new Document(text))));

        assertEquals(3, chunks.size(), "20 字符按 8 切应得到 3 块");
        assertEquals("01234567", chunks.get(0));
        assertEquals("89ABCDEF", chunks.get(1));
        assertEquals("GHIJ", chunks.get(2));
    }

    @Test
    @DisplayName("固定大小：支持重叠")
    void 固定大小支持重叠() {
        String text = "0123456789ABCDEFGHIJ";
        FixedSizeTextSplitter splitter = new FixedSizeTextSplitter(8, 2);

        List<String> chunks = texts(splitter.apply(List.of(new Document(text))));

        // 首尾应共享字符：第二块以第一块末尾的 "67" 开头
        assertEquals("6789ABCD", chunks.get(1), "重叠 2 字符，第二块应从第一块末尾 2 字符开始");
    }

    // ==================== 2. 递归字符 ====================

    @Test
    @DisplayName("递归字符：在段落边界切分")
    void 递归字符在段落边界切分() {
        String text = "第一段内容\n\n第二段内容\n\n第三段内容";
        RecursiveCharacterTextSplitter splitter = new RecursiveCharacterTextSplitter(10, 0);

        List<String> chunks = texts(splitter.apply(List.of(new Document(text))));

        assertEquals(3, chunks.size(), "三个段落应切成三块");
        assertEquals("第一段内容", chunks.get(0).trim());
        assertEquals("第二段内容", chunks.get(1).trim());
        assertEquals("第三段内容", chunks.get(2).trim());
    }

    @Test
    @DisplayName("递归字符：每块不超过 chunkSize")
    void 递归字符每块不超过限制() {
        // 连续无分隔符的长文本，兜底硬切
        String text = "A".repeat(100);
        RecursiveCharacterTextSplitter splitter = new RecursiveCharacterTextSplitter(30, 0);

        List<String> chunks = texts(splitter.apply(List.of(new Document(text))));

        assertTrue(chunks.size() > 1, "长文本应切成多块");
        assertTrue(chunks.stream().allMatch(c -> c.length() <= 30), "每块长度不应超过 chunkSize");
    }

    // ==================== 3. 结构感知 ====================

    @Test
    @DisplayName("结构感知：按标题切分且标题跟随内容")
    void 结构感知按标题切分() {
        String text = String.join("\n",
                "# 苹果公司介绍",
                "苹果是一家美国科技公司，主要生产手机和电脑。",
                "",
                "## 苹果产品线",
                "iPhone 是苹果的核心产品。",
                "",
                "# 汽车行业介绍",
                "特斯拉是电动车领域的先行者。");

        StructureAwareSplitter splitter = new StructureAwareSplitter(200);
        List<String> chunks = texts(splitter.apply(List.of(new Document(text))));

        assertEquals(3, chunks.size(), "三个标题应切成三块");
        assertTrue(chunks.get(0).startsWith("# 苹果公司介绍"), "标题应位于块首");
        assertTrue(chunks.get(1).contains("## 苹果产品线"));
        assertTrue(chunks.get(2).startsWith("# 汽车行业介绍"));

        // 结构感知的核心：不把不同主题混在一块
        for (String c : chunks) {
            assertFalse(c.contains("苹果") && c.contains("汽车"),
                    "苹果与汽车主题不应混在同一块： " + c);
        }
    }

    // ==================== 4. 语义分块 ====================

    @Test
    @DisplayName("语义分块：按语义断点切分")
    void 语义分块按语义断点切分() {
        // 前 3 句讲苹果，后 3 句讲特斯拉，语义转折处应断开
        String text = "苹果发布了新款iPhone。这款手机屏幕更大。电池续航也更长。"
                + "特斯拉发布了新款电动车。这款车续航超过500公里。充电速度也更快。";

        EmbeddingModel embeddingModel = mockEmbedding();
        SemanticChunkingSplitter splitter = new SemanticChunkingSplitter(embeddingModel, 512, 0.5);

        List<String> chunks = texts(splitter.apply(List.of(new Document(text))));

        assertEquals(2, chunks.size(), "语义转折处应切成两块");
        assertTrue(chunks.get(0).contains("苹果") && !chunks.get(0).contains("特斯拉"),
                "第一块应是苹果主题");
        assertTrue(chunks.get(1).contains("特斯拉") && !chunks.get(1).contains("苹果"),
                "第二块应是特斯拉主题");
    }

    // ==================== 5. 父子文档 ====================

    @Test
    @DisplayName("父子文档：子块记录父块信息")
    void 父子文档子块记录父块信息() {
        // 足够长的文本，确保能切出多个父块
        String para = "这是关于企业知识库的一段说明文字，内容足够长，用来测试父子文档分块能否正确切出父块和子块。";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            sb.append(para);
        }
        String source = "测试文档.txt";

        ParentChildChunkingService service = new ParentChildChunkingService();
        ParentChildChunkingService.ParentChildChunks result =
                service.chunk(List.of(new Document(sb.toString())), source);

        assertTrue(result.parents().size() >= 2, "长文档应切出多个父块");
        assertTrue(result.children().size() > result.parents().size(),
                "子块应比父块更细（更多）");

        for (Document child : result.children()) {
            // 关键契约：每个子块都能反查父块
            assertNotNull(child.getMetadata().get("parent_id"), "子块应记录 parent_id");
            assertNotNull(child.getMetadata().get("parent_content"), "子块应记录父块全文");
            assertEquals(source, child.getMetadata().get("source"), "子块应记录来源");

            String parentContent = (String) child.getMetadata().get("parent_content");
            assertTrue(parentContent.length() >= child.getText().length(),
                    "父块全文不应短于子块");
        }
    }

    // ==================== 工具 ====================

    /** 提取 Document 列表的文本 */
    private List<String> texts(List<Document> docs) {
        return docs.stream().map(Document::getText).toList();
    }

    /**
     * Mock EmbeddingModel：按关键词返回可区分的向量。
     * <ul>
     *   <li>苹果相关句子 → [1, 0]</li>
     *   <li>汽车相关句子 → [0, 1]</li>
     * </ul>
     * 两者余弦相似度为 0，从而在主题切换处触发语义断点。
     */
    private EmbeddingModel mockEmbedding() {
        EmbeddingModel model = Mockito.mock(EmbeddingModel.class);
        when(model.embed(anyList())).thenAnswer(inv -> {
            List<String> sentences = inv.getArgument(0);
            return sentences.stream().map(this::vectorFor).toList();
        });
        return model;
    }

    private float[] vectorFor(String sentence) {
        if (sentence.contains("苹果") || sentence.contains("iPhone")
                || sentence.contains("手机") || sentence.contains("电池")) {
            return new float[]{1.0f, 0.0f};
        }
        if (sentence.contains("特斯拉") || sentence.contains("电动车")
                || sentence.contains("车") || sentence.contains("充电")) {
            return new float[]{0.0f, 1.0f};
        }
        return new float[]{0.5f, 0.5f};
    }
}
