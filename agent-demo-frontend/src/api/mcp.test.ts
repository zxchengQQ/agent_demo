// @vitest-environment node
import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  getServers,
  addServer,
  deleteServer,
  reconnectServer,
  getServerTools,
} from './mcp';
import type {
  McpServerInfo,
  McpToolInfo,
  CreateMcpServerRequest,
} from '@/types';

/**
 * MCP 服务管理 API 封装测试
 * 验证每个函数的请求路径、方法和响应解析。
 */

/** mock fetch 的辅助方法，构造 Result<T> 响应 */
function mockFetchSuccess<T>(data: T): void {
  vi.mocked(global.fetch).mockResolvedValueOnce({
    ok: true,
    json: async () => ({ success: true, code: 200, message: '成功', data, traceId: 'test' }),
  } as Response);
}

/** mock fetch 业务错误响应 */
function mockFetchBusinessError(message: string): void {
  vi.mocked(global.fetch).mockResolvedValueOnce({
    ok: true,
    json: async () => ({ success: false, code: 5401, message, data: null, traceId: 'test' }),
  } as Response);
}

/** mock fetch 网络错误响应 */
function mockFetchNetworkError(): void {
  vi.mocked(global.fetch).mockResolvedValueOnce({
    ok: false,
    json: async () => { throw new Error('parse error'); },
  } as Response);
}

/** 构造测试用 McpServerInfo */
function makeServer(name = 'fetch'): McpServerInfo {
  return {
    name,
    transport: 'STDIO',
    status: 'CONNECTED',
    enabled: true,
    toolCount: 2,
    lastError: null,
    connectTime: '2026-08-07T10:00:00',
    url: null,
  command: 'npx',
  args: ['mcp-fetch-server'],
};
}

/** 构造测试用 CreateMcpServerRequest */
function makeAddRequest(): CreateMcpServerRequest {
  return {
    name: 'fetch',
    transport: 'STDIO',
    enabled: true,
    command: 'npx',
    args: ['mcp-fetch-server'],
  };
}

beforeEach(() => {
  vi.restoreAllMocks();
  global.fetch = vi.fn();
});

describe('MCP 服务管理 API 封装', () => {
  it('getServers 发送 GET /api/mcp/servers 并返回 Server 列表', async () => {
    const mockList: McpServerInfo[] = [makeServer()];
    mockFetchSuccess(mockList);

    const result = await getServers();
    expect(global.fetch).toHaveBeenCalledWith('/api/mcp/servers', undefined);
    expect(result).toEqual(mockList);
  });

  it('addServer 发送 POST /api/mcp/servers 并返回新 Server', async () => {
    const mockServer = makeServer();
    mockFetchSuccess(mockServer);
    const data = makeAddRequest();

    const result = await addServer(data);
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/mcp/servers',
      expect.objectContaining({
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
      }),
    );
    expect(result).toEqual(mockServer);
  });

  it('deleteServer 发送 DELETE /api/mcp/servers/{name}', async () => {
    mockFetchSuccess(null);

    await deleteServer('fetch');
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/mcp/servers/fetch',
      expect.objectContaining({ method: 'DELETE' }),
    );
  });

  it('deleteServer 对特殊字符名称进行 URL 编码', async () => {
    mockFetchSuccess(null);

    await deleteServer('my server');
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/mcp/servers/my%20server',
      expect.objectContaining({ method: 'DELETE' }),
    );
  });

  it('reconnectServer 发送 POST /api/mcp/servers/{name}/reconnect 并返回重连后的 Server', async () => {
    const mockServer = makeServer();
    mockFetchSuccess(mockServer);

    const result = await reconnectServer('fetch');
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/mcp/servers/fetch/reconnect',
      expect.objectContaining({ method: 'POST' }),
    );
    expect(result).toEqual(mockServer);
  });

  it('getServerTools 发送 GET /api/mcp/servers/{name}/tools 并返回工具列表', async () => {
    const mockTools: McpToolInfo[] = [
      { originalName: 'fetch', registeredName: 'mcp_fetch_fetch', description: '抓取网页', parametersSchema: '{"type":"object"}' },
    ];
    mockFetchSuccess(mockTools);

    const result = await getServerTools('fetch');
    expect(global.fetch).toHaveBeenCalledWith('/api/mcp/servers/fetch/tools', undefined);
    expect(result).toEqual(mockTools);
  });

  it('后端返回业务错误时抛出异常', async () => {
    mockFetchBusinessError('MCP Server 名称已存在');
    await expect(addServer(makeAddRequest())).rejects.toThrow('MCP Server 名称已存在');
  });

  it('网络请求失败时抛出异常', async () => {
    mockFetchNetworkError();
    await expect(getServers()).rejects.toThrow('网络异常，请稍后重试');
  });
});
