package com.agentdemo.app.strategy;

import com.agentdemo.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子任务 JSON 解析器测试（P3 Task-13，三层容错，AC-007）
 * <p>
 * 业务含义：主控拆解输出是 LLM 自由文本（可能带 markdown 围栏/前后噪声/超量），
 * 解析器须稳定提取子任务列表；解析失败可重试（上层 planWithRetry）。
 * </p>
 */
class SubtaskParserTest {

    @Test
    void parse_standardJson_shouldReturnSubtasks() {
        String raw = "[{\"id\":1,\"description\":\"调研分类算法\",\"agent\":\"研究\"}]";

        List<com.agentdemo.app.core.Subtask> result = SubtaskParser.parse(raw, 5);

        assertEquals(1, result.size());
        assertEquals(1, result.get(0).getId());
        assertEquals("调研分类算法", result.get(0).getDescription());
        assertEquals("研究", result.get(0).getAgent());
    }

    @Test
    void parse_markdownWrapped_shouldExtractAndParse() {
        String raw = "```json\n[{\"id\":1,\"description\":\"调研\",\"agent\":\"研究\"}," +
                "{\"id\":2,\"description\":\"对比\",\"agent\":\"分析\"}]\n```";

        List<com.agentdemo.app.core.Subtask> result = SubtaskParser.parse(raw, 5);

        assertEquals(2, result.size());
        assertEquals("对比", result.get(1).getDescription());
    }

    @Test
    void parse_prefixAndSuffixNoise_shouldExtractBracketRange() {
        String raw = "好的，以下是拆解：\n[{\"id\":1,\"description\":\"调研\",\"agent\":\"研究\"}]\n希望有帮助";

        List<com.agentdemo.app.core.Subtask> result = SubtaskParser.parse(raw, 5);

        assertEquals(1, result.size());
        assertEquals("调研", result.get(0).getDescription());
    }

    @Test
    void parse_exceedMaxSubtasks_shouldTruncate() {
        String raw = "[" +
                "{\"id\":1,\"description\":\"t1\",\"agent\":\"研究\"}," +
                "{\"id\":2,\"description\":\"t2\",\"agent\":\"研究\"}," +
                "{\"id\":3,\"description\":\"t3\",\"agent\":\"分析\"}," +
                "{\"id\":4,\"description\":\"t4\",\"agent\":\"分析\"}," +
                "{\"id\":5,\"description\":\"t5\",\"agent\":\"总结\"}," +
                "{\"id\":6,\"description\":\"t6\",\"agent\":\"研究\"}," +
                "{\"id\":7,\"description\":\"t7\",\"agent\":\"分析\"}" +
                "]";

        List<com.agentdemo.app.core.Subtask> result = SubtaskParser.parse(raw, 5);

        assertEquals(5, result.size(), "超出 maxSubtasks 应截断为前 5 个");
        assertEquals("t5", result.get(4).getDescription());
    }

    @Test
    void parse_blankDescriptionFiltered_andMissingIdBackfilled() {
        String raw = "[" +
                "{\"id\":1,\"description\":\"t1\",\"agent\":\"研究\"}," +
                "{\"id\":2,\"description\":\"\",\"agent\":\"分析\"}," +
                "{\"description\":\"t3\",\"agent\":\"总结\"}" +
                "]";

        List<com.agentdemo.app.core.Subtask> result = SubtaskParser.parse(raw, 5);

        assertEquals(2, result.size(), "description 为空项应被过滤");
        assertEquals("t1", result.get(0).getDescription());
        assertEquals(2, result.get(1).getId(), "id 缺失项应按下标补齐（保留序）");
        assertEquals("t3", result.get(1).getDescription());
    }

    @Test
    void parse_noBracket_shouldThrowWithUnparsableMessage() {
        String raw = "这个任务不需要拆解，直接执行即可";

        BusinessException ex = assertThrows(BusinessException.class,
                () -> SubtaskParser.parse(raw, 5));
        assertTrue(ex.getMessage().contains("无法解析"),
                "异常 message 应含\"无法解析\"，实际: " + ex.getMessage());
    }

    @Test
    void parse_emptyArray_shouldThrow() {
        assertThrows(BusinessException.class, () -> SubtaskParser.parse("[]", 5));
    }

    @Test
    void parse_pureTextWithoutJson_shouldThrow() {
        assertThrows(BusinessException.class, () -> SubtaskParser.parse("纯文本输出", 5));
    }

    @Test
    void parse_unknownFields_shouldBeIgnored() {
        String raw = "[{\"id\":1,\"description\":\"x\",\"agent\":\"研究\",\"priority\":1,\"extra\":\"y\"}]";

        List<com.agentdemo.app.core.Subtask> result = SubtaskParser.parse(raw, 5);

        assertEquals(1, result.size());
        assertEquals("研究", result.get(0).getAgent());
    }

    @Test
    void parse_nullInput_shouldThrow() {
        assertNotNull(assertThrows(BusinessException.class,
                () -> SubtaskParser.parse(null, 5)));
    }
}
