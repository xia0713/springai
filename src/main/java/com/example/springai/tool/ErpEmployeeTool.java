package com.example.springai.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ERP 员工查询工具（Day53）—— 模型世界与 HTTP 世界的适配层。
 * <p>
 * 五件事：参数映射 / 接口鉴权 / 超时控制 / 结果格式化 / 错误可读化。
 * 关键红线：鉴权密钥来自配置，绝不出现在模型上下文（防注入套话泄露）。
 */
public class ErpEmployeeTool {

    private final RestClient erpClient;
    private final String apiKey;

    /** 参数映射白名单：模型说"研发"，接口要 deptCode（防模型编造编码） */
    private static final Map<String, String> DEPT_CODES = Map.of(
            "研发", "RD", "销售", "SALES", "财务", "FIN");
    private static final Map<String, String> DEPT_NAMES = Map.of(
            "RD", "研发部", "SALES", "销售部", "FIN", "财务部");

    public ErpEmployeeTool(@Value("${app.erp.base-url}") String baseUrl,
                           @Value("${app.erp.api-key}") String apiKey) {
        this.apiKey = apiKey;
        // 坑1：不配超时，慢接口拖死整个工具调用。connect 3s / read 5s 起步
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.erpClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    @Tool(description = "查询公司员工信息（姓名/部门/职位）。支持按部门查询，部门只支持：研发、销售、财务")
    public String queryEmployee(
            @ToolParam(description = "部门名称，只支持：研发、销售、财务；不传查全部") String department) {

        // ① 参数映射：模型给业务词，接口要编码。查不到映射 → 可读提示（可预期错误不抛异常）
        String deptCode = DEPT_CODES.get(department);
        if (department != null && !department.isBlank() && deptCode == null) {
            return "不支持的部门：" + department + "，目前只支持：研发、销售、财务";
        }
        String deptName = deptCode == null ? null : DEPT_NAMES.get(deptCode);

        // ② 调用接口：鉴权头在代码里加（密钥不进模型上下文）
        Map<String, Object> resp;
        try {
            resp = erpClient.get()
                    .uri(uri -> {
                        var b = uri.path("/api/mock-erp/employees");
                        if (deptName != null) {
                            b.queryParam("department", deptName);
                        }
                        return b.queryParam("page", 1).queryParam("size", 100).build();
                    })
                    .header("X-API-KEY", apiKey)                 // 鉴权：密钥只在这里出现
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            // 坑2：接口报错/超时 → 可读错误回传（走 Day51 降级，模型能转述，不 500）
            return "员工查询接口调用失败：" + e.getClass().getSimpleName() + "，请稍后再试";
        }

        // 接口层错误码检查
        if (resp == null || !Integer.valueOf(0).equals(resp.get("code"))) {
            return "员工查询接口返回异常：" + (resp == null ? "空响应" : resp.get("message"));
        }

        // ③ 结果格式化：大结果是上下文杀手 —— 挑字段 + 限条数 + 转表格
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        int total = ((Number) data.get("total")).intValue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) data.get("records");

        int limit = Math.min(5, records.size());   // 坑3：不限条数，token 爆炸
        String table = records.stream()
                .limit(limit)
                .map(r -> "| %s | %s | %s |".formatted(
                        r.get("name"), r.get("department"), r.get("title")))
                .collect(Collectors.joining("\n"));

        return "查询到 %d 名员工，显示前 %d 名：\n| 姓名 | 部门 | 职位 |\n|---|---|---|\n%s"
                .formatted(total, limit, table);
    }
}
