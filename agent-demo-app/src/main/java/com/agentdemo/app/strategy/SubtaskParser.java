package com.agentdemo.app.strategy;

import com.agentdemo.app.core.Subtask;
import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 子任务 JSON 解析器（P3 Task-13，三层容错，AC-007）
 * <p>
 * 业务含义：主控拆解输出是 LLM 自由文本，格式可能漂移（markdown 围栏/前后噪声/
 * 未知字段/超量）。三层防线：① 剥离 markdown 代码块 ② 提取 [ ] 区间 ③ Jackson 容错解析；
 * 解析失败抛 BusinessException 由上层 planWithRetry 重试（给 LLM 纠正机会）。
 * </p>
 */
public final class SubtaskParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SubtaskParser() {
    }

    /**
     * 解析主控拆解输出为子任务列表
     *
     * @param raw         主控 PlanAgent 的原始输出
     * @param maxSubtasks 子任务数量上限（超出截断，防 LLM 拆解失控）
     * @return 子任务列表（已过滤空项、补齐缺失 id、按上限截断）
     * @throws BusinessException 无法解析出非空子任务列表时（message 含"无法解析"）
     */
    public static List<Subtask> parse(String raw, int maxSubtasks) {
        if (raw == null || raw.isBlank()) {
            throw parseFailed(raw);
        }
        String content = stripMarkdownFence(raw);
        String json = extractJsonArrayRange(content);

        List<Subtask> subtasks = new ArrayList<>();
        try {
            JsonNode arr = MAPPER.readTree(json);
            if (!arr.isArray()) {
                throw parseFailed(raw);
            }
            int seq = 0;
            for (JsonNode node : arr) {
                String desc = node.path("description").asText(null);
                // 过滤 description 为空项（LLM 输出的占位/无效项）
                if (desc == null || desc.isBlank()) {
                    continue;
                }
                seq++;
                // id 缺失或非法时按下标补齐（1..N，按保留序）
                int id = node.path("id").isInt() ? node.path("id").asInt() : seq;
                String agent = node.hasNonNull("agent") ? node.path("agent").asText() : null;
                subtasks.add(Subtask.builder().id(id).description(desc.trim()).agent(agent).build());
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw parseFailed(raw);
        }

        if (subtasks.isEmpty()) {
            throw parseFailed(raw);
        }
        if (maxSubtasks > 0 && subtasks.size() > maxSubtasks) {
            subtasks = new ArrayList<>(subtasks.subList(0, maxSubtasks));
        }
        return subtasks;
    }

    /**
     * 第一层容错：剥离 markdown 代码块围栏（提取最后一对 ``` 之间的内容）
     */
    private static String stripMarkdownFence(String raw) {
        if (!raw.contains("```")) {
            return raw;
        }
        int last = raw.lastIndexOf("```");
        int prev = raw.lastIndexOf("```", last - 1);
        if (prev < 0) {
            return raw;  // 围栏不成对，交给下一层提取
        }
        return raw.substring(prev + 3, last);
    }

    /**
     * 第二层容错：提取首个 '[' 到最后一个 ']' 的区间（剥离前后缀噪声）
     */
    private static String extractJsonArrayRange(String content) {
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start < 0 || end < 0 || end <= start) {
            throw parseFailed(content);
        }
        return content.substring(start, end + 1);
    }

    private static BusinessException parseFailed(String raw) {
        return new BusinessException(ErrorCode.WORKFLOW_EXECUTION_FAILED,
                "主控拆解输出无法解析为子任务列表: "
                        + (raw != null && raw.length() > 200 ? raw.substring(0, 200) + "..." : raw));
    }
}
