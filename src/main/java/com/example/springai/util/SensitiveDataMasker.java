package com.example.springai.util;

import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感数据脱敏器（Day80）：手机号/身份证/银行卡/邮箱，双向使用（入站+出站）。
 * <p>
 * 规则顺序 = 正确性：身份证(18) → 银行卡(16-19) → 手机号(11)，从长到短——
 * 身份证若被银行卡规则先处理就丢失语义。
 * 词边界 (?<!\d)...(?!\d) 防误伤（订单号片段/时间戳，Day44 正则边界教训同源）。
 */
public final class SensitiveDataMasker {

    private static final Pattern ID_CARD =
            Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");
    private static final Pattern BANK_CARD =
            Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");
    private static final Pattern PHONE =
            Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private SensitiveDataMasker() {}

    /** 双向入口：入站（发给 LLM 前）与出站（返回用户/进日志前）都调这个 */
    public static String mask(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String s = ID_CARD.matcher(text).replaceAll(m -> keep(m, 6, 4));
        s = BANK_CARD.matcher(s).replaceAll(m -> keep(m, 4, 4));
        s = PHONE.matcher(s).replaceAll(m -> keep(m, 3, 4));
        s = EMAIL.matcher(s).replaceAll(m -> {
            String e = m.group();
            return e.charAt(0) + "***" + e.substring(e.indexOf('@'));
        });
        return s;
    }

    /** 保留头尾、中间打星（至少 3 个星，短串不暴露长度信息） */
    private static String keep(MatchResult m, int head, int tail) {
        String s = m.group();
        int stars = Math.max(3, s.length() - head - tail);
        return s.substring(0, Math.min(head, s.length()))
                + "*".repeat(stars)
                + (s.length() > head + tail ? s.substring(s.length() - tail) : "");
    }
}
