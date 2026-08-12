import { defineStore } from 'pinia';
import * as mcpApi from '@/api/mcp';
import type { McpServerInfo, McpToolInfo } from '@/types';

/**
 * MCP 服务管理状态
 *
 * 业务含义：作为跨组件共享的 MCP Server 数据唯一来源，封装 API 调用。
 * 写操作（删除/重连）后自动刷新 servers 列表，保证页面数据一致性（AC-034）。
 */

export const useMcpStore = defineStore('mcp', {
  state: () => ({
    /** 已配置的 Server 列表 */
    servers: [] as McpServerInfo[],
    /** 列表加载中状态 */
    loading: false,
    /** 各 Server 的工具列表缓存（按 name 索引） */
    toolsCache: {} as Record<string, McpToolInfo[]>,
    /** 当前加载工具列表的 Server 名（展开中） */
    loadingTools: '' as string,
  }),

  actions: {
    /**
     * 加载 Server 列表（AC-004）
     * 业务含义：从后端拉取所有 MCP Server，调用失败时保持空列表不崩溃。
     */
    async loadServers() {
      this.loading = true;
      try {
        this.servers = await mcpApi.getServers();
      } catch (e) {
        // 保持空列表，错误由页面组件通过 try-catch 捕获提示
        this.servers = [];
        throw e;
      } finally {
        this.loading = false;
      }
    },

    /**
     * 删除 Server（AC-012）
     * 业务含义：调用后端删除接口后，自动刷新列表并清除该 Server 的工具缓存。
     */
    async deleteServer(name: string) {
      await mcpApi.deleteServer(name);
      delete this.toolsCache[name];
      await this.loadServers();
    },

    /**
     * 重连 Server（AC-014）
     * 业务含义：调用后端重连接口后，自动刷新列表（状态更新为 CONNECTED）。
     */
    async reconnectServer(name: string) {
      this.loading = true;
      try {
        await mcpApi.reconnectServer(name);
        await this.loadServers();
      } finally {
        this.loading = false;
      }
    },

    /**
     * 加载指定 Server 的工具列表（AC-018）
     * 业务含义：按需拉取工具列表并缓存到 toolsCache，展开详情时展示。
     */
    async loadServerTools(name: string) {
      this.loadingTools = name;
      try {
        const tools = await mcpApi.getServerTools(name);
        this.toolsCache[name] = tools;
        return tools;
      } finally {
        this.loadingTools = '';
      }
    },

    /**
     * 清空工具缓存
     * 业务含义：删除 Server 或需强制刷新时调用，避免展示过期工具列表。
     */
    clearToolsCache() {
      this.toolsCache = {};
    },
  },
});
