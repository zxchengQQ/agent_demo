import { describe, it, expect, beforeEach, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

/**
 * MCP 服务管理状态测试
 *
 * 测试策略：mock '../api/mcp' 模块，仅验证 store 的 state 流转。
 */

vi.mock('../api/mcp', () => ({
  getServers: vi.fn(),
  addServer: vi.fn(),
  deleteServer: vi.fn(),
  reconnectServer: vi.fn(),
  getServerTools: vi.fn(),
}));

import { useMcpStore } from './mcp';
import {
  getServers,
  deleteServer,
  reconnectServer,
  getServerTools,
} from '../api/mcp';
import type { McpServerInfo, McpToolInfo } from '@/types';

/** Server mock 数据 */
const mockServer: McpServerInfo = {
  name: 'fetch',
  transport: 'STDIO',
  status: 'CONNECTED',
  enabled: true,
  toolCount: 1,
  lastError: null,
  connectTime: '2026-08-07T10:00:00',
  url: null,
  command: 'npx',
  args: ['mcp-fetch-server'],
};

beforeEach(() => {
  setActivePinia(createPinia());
  vi.clearAllMocks();
});

describe('MCP 服务管理 Store', () => {
  it('loadServers 成功时更新 servers 列表并切换 loading 状态', async () => {
    const store = useMcpStore();
    const mockList = [mockServer];
    vi.mocked(getServers).mockResolvedValueOnce(mockList);

    await store.loadServers();

    expect(store.servers).toEqual(mockList);
    expect(store.loading).toBe(false);
  });

  it('loadServers 失败时 servers 保持空数组且不崩溃', async () => {
    const store = useMcpStore();
    vi.mocked(getServers).mockRejectedValueOnce(new Error('网络异常'));

    await expect(store.loadServers()).rejects.toThrow('网络异常');
    expect(store.servers).toEqual([]);
    expect(store.loading).toBe(false);
  });

  it('deleteServer 删除后自动刷新列表并清除工具缓存', async () => {
    const store = useMcpStore();
    store.toolsCache['fetch'] = [{
      originalName: 'fetch', registeredName: 'mcp_fetch_fetch', description: '', parametersSchema: '',
    }];
    vi.mocked(deleteServer).mockResolvedValueOnce();
    vi.mocked(getServers).mockResolvedValueOnce([mockServer]);

    await store.deleteServer('fetch');

    expect(deleteServer).toHaveBeenCalledWith('fetch');
    expect(getServers).toHaveBeenCalled();
    expect(store.toolsCache['fetch']).toBeUndefined();
  });

  it('reconnectServer 重连成功后自动刷新列表', async () => {
    const store = useMcpStore();
    vi.mocked(reconnectServer).mockResolvedValueOnce(mockServer);
    vi.mocked(getServers).mockResolvedValueOnce([mockServer]);

    await store.reconnectServer('fetch');

    expect(reconnectServer).toHaveBeenCalledWith('fetch');
    expect(getServers).toHaveBeenCalled();
    expect(store.loading).toBe(false);
  });

  it('loadServerTools 成功时缓存工具列表并返回', async () => {
    const store = useMcpStore();
    const mockTools: McpToolInfo[] = [
      { originalName: 'fetch', registeredName: 'mcp_fetch_fetch', description: '抓取', parametersSchema: '{}' },
    ];
    vi.mocked(getServerTools).mockResolvedValueOnce(mockTools);

    const result = await store.loadServerTools('fetch');

    expect(result).toEqual(mockTools);
    expect(store.toolsCache['fetch']).toEqual(mockTools);
    expect(store.loadingTools).toBe('');
  });

  it('clearToolsCache 清空所有工具缓存', () => {
    const store = useMcpStore();
    store.toolsCache['fetch'] = [{
      originalName: 'fetch', registeredName: 'mcp_fetch_fetch', description: '', parametersSchema: '',
    }];

    store.clearToolsCache();

    expect(store.toolsCache).toEqual({});
  });
});
