package com.agentdemo.tools.registry;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具标识解析器（toolId 构建的领域内聚组件）
 * <p>
 * 业务含义：将 @Tool 方法名映射为权限标识 id（category:name 格式），供 ToolRegistry
 * （解析/过滤/元数据）与 ToolPermissionGuard（执行期权限捕获）共同使用，避免两套标识
 * 逻辑漂移，同时打破 ToolRegistry ↔ ToolPermissionGuard 的循环依赖。
 * </p>
 * <p>
 * MCP 工具方法名格式为 mcp_{serverName}_{toolName}，toolName 可能含下划线，无法从方法名
 * 可靠反推 serverName。因此动态注册时经 {@link #registerServerName} 显式登记 serverName，
 * buildToolId 优先使用登记值构建准确的 mcp:{serverName} 标识。
 * </p>
 */
@Component
public class ToolIdResolver {

    /** MCP 工具方法名 → MCP Server 名称 映射（动态注册时登记） */
    private final Map<String, String> serverNamesByMethod = new HashMap<>();

    /**
     * 登记 MCP 工具方法名对应的 Server 名称
     *
     * @param methodName 工具方法名（如 mcp_mermaid_flowchart）
     * @param serverName MCP Server 名称（如 mermaid-mcp）
     */
    public void registerServerName(String methodName, String serverName) {
        serverNamesByMethod.put(methodName, serverName);
    }

    /**
     * 查询指定 MCP Server 下的全部工具方法名（供 mcp:{serverName} 精确匹配解析）
     */
    public List<String> methodNamesOfServer(String serverName) {
        return serverNamesByMethod.entrySet().stream()
                .filter(e -> serverName.equals(e.getValue()))
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * 推断工具类别
     * <p>业务含义：方法名前缀决定类别——mcp_ 前缀归 MCP 类，kb_ 前缀归知识库类（rag），其余为内置工具。</p>
     */
    public String inferCategory(String methodName) {
        if (methodName.startsWith("mcp_")) {
            return "mcp";
        }
        if (methodName.startsWith("kb_")) {
            return "rag";
        }
        return "builtin";
    }

    /**
     * 构建工具标识 id（category:name 格式）
     * <p>业务含义：权限判定与前端展示共用同一标识体系；MCP 优先用登记的 serverName（工具名可能含下划线）。</p>
     */
    public String buildToolId(String category, String methodName) {
        switch (category) {
            case "mcp":
                String serverName = serverNamesByMethod.get(methodName);
                if (serverName != null && !serverName.isBlank()) {
                    return "mcp:" + serverName;
                }
                // 回退：方法名去掉 mcp_ 前缀取首段
                String noPrefix = methodName.substring(4);
                int firstUnderscore = noPrefix.indexOf('_');
                if (firstUnderscore > 0) {
                    return "mcp:" + noPrefix.substring(0, firstUnderscore);
                }
                return "mcp:" + noPrefix;
            case "rag":
                return "rag:" + methodName.substring(3);
            default:
                return "builtin:" + methodName;
        }
    }
}
