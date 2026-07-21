package com.agentdemo.tools.builtin;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 计算器工具
 * <p>
 * 业务含义：提供数学表达式计算能力，Agent 可自主决定何时调用。
 * 基于 Spring SpEL（Spring Expression Language）实现，支持四则运算、括号、幂运算等。
 * </p>
 */
@Component
public class CalculatorTool {

    /**
     * 计算数学表达式
     * 业务含义：将用户输入的表达式转换为 SpEL 可解析的形式并计算结果
     *
     * @param expression 数学表达式，如 "2+3"、"5*6"、"2^10"
     * @return 计算结果字符串
     */
    @Tool("计算数学表达式，支持加减乘除、括号、幂运算(用 ^ 表示幂)，例如：2+3、(2+3)*4、2^10")
    public String calculate(String expression) {
        try {
            // 业务含义：将 ^ 转换为 Math.pow 调用，因为 SpEL 不原生支持 ^ 幂运算
            String spelExpr = expression
                    .replaceAll("(\\d+(?:\\.\\d+)?)\\s*\\^\\s*(\\d+(?:\\.\\d+)?)",
                            "T(java.lang.Math).pow($1,$2)");
            org.springframework.expression.ExpressionParser parser =
                    new org.springframework.expression.spel.standard.SpelExpressionParser();
            org.springframework.expression.Expression exp = parser.parseExpression(spelExpr);
            Object result = exp.getValue();
            return expression + " = " + result;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "表达式计算失败: " + expression, e);
        }
    }
}
