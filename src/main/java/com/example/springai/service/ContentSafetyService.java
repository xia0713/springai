package com.example.springai.service;

import com.example.springai.util.SensitiveDataMasker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 内容安全服务（Day80）：规则层审核 + 双向脱敏。
 * <p>
 * 审计纪律：拦截事件只记命中的规则类别，【绝不记录违规原文】——
 * 原文本身就是最敏感的数据（Day79"日志别打敏感全文"的红线延续）。
 * 分层（Day65）：规则层(今天) → LLM 语义层(进阶：变体/意图类) → 人工申诉复核。
 * 进阶：可逆脱敏（占位符化 + 本地映射表，功能与合规兼得）记入综合项目。
 */
@Slf4j
@Service
public class ContentSafetyService {

    /** 敏感词黑名单（演示；生产用专业风控服务 + 热更新词库） */
    private static final Set<String> BLOCK_WORDS =
            Set.of("枪支", "毒品", "爆炸物", "武器制造");

    /** 审核结果：passed=false 时 maskedQuestion 为 null（不给下游任何原文） */
    public record SafetyResult(boolean passed, String maskedQuestion, String reason) {}

    /** 入站：违规拦截 → 通过则脱敏（敏感数据不出域第一道） */
    public SafetyResult checkInput(String question) {
        // ① 规则层：违规词拦截
        for (String word : BLOCK_WORDS) {
            if (question != null && question.contains(word)) {
                // 审计只记规则类别，不记原文
                log.warn("[safety] 拦截违规内容，命中规则类别=BLOCK_WORD（原文不记录）");
                return new SafetyResult(false, null,
                        "内容包含违规信息，已被拦截。如有疑问请联系管理员申诉。");
            }
        }
        // ② 入站脱敏：敏感数据打码后才发给第三方 LLM（数据不出域第一道）
        return new SafetyResult(true, SensitiveDataMasker.mask(question), "通过");
    }

    /** 出站：LLM 可能复述用户给的敏感数据 → 返回用户/进日志前复扫 */
    public String maskOutput(String answer) {
        return SensitiveDataMasker.mask(answer);
    }
}
