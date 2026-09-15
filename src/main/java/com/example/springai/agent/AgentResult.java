package com.example.springai.agent;

/**
 * Agent 执行结果（Day62 升级）：terminateReason 标明终止原因。
 * completed=false 时，answer 是【人工介入单】而非异常信息 —— 熔断即交接班。
 */
public record AgentResult(boolean completed, int stepsUsed, String answer, String terminateReason) {

    public static final String SUCCESS = "SUCCESS";
    public static final String MAX_STEPS_HIT = "MAX_STEPS";
    public static final String REPEAT_LOOP = "REPEAT_LOOP";
    public static final String TIME_BUDGET = "TIME_BUDGET";
    public static final String TOOL_ERROR = "TOOL_ERROR";

    public static AgentResult success(String answer, int steps) {
        return new AgentResult(true, steps, answer, SUCCESS);
    }

    public static AgentResult exhausted(int maxSteps) {
        return humanIntervention(MAX_STEPS_HIT, maxSteps,
                "任务在 " + maxSteps + " 步内未能完成");
    }

    /** 统一的人工介入出口：answer 即介入单（含轨迹/卡点/建议） */
    public static AgentResult humanIntervention(String reason, int steps, String detail) {
        return new AgentResult(false, steps, detail, reason);
    }
}
