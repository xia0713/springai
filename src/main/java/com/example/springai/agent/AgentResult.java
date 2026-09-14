package com.example.springai.agent;

/**
 * Agent 执行结果（Day58）：completed=false 表示步数耗尽被熔断，需人工介入。
 */
public record AgentResult(boolean completed, int stepsUsed, String answer) {

    public static AgentResult success(String answer, int steps) {
        return new AgentResult(true, steps, answer);
    }

    public static AgentResult exhausted(int maxSteps) {
        return new AgentResult(false, maxSteps,
                "任务在 " + maxSteps + " 步内未能完成，已熔断，需人工介入");
    }
}
