import { describe, it, expect } from 'vitest';
import { parseMcpServersConfig, parseSingleServer } from './mcp-config';
import type { CreateMcpServerRequest } from '@/types';

/**
 * MCP JSON 配置解析工具测试（Task-06）
 * 验证标准来源：Task-06 验证标准
 * 关联 AC：AC-008, AC-009, AC-010, AC-011, AC-019, AC-020, AC-021,
 *          AC-029, AC-030, AC-031, AC-032, AC-033
 */

describe('parseMcpServersConfig JSON 解析', () => {
  it('非法 JSON 抛出 JSON 格式错误（AC-019）', () => {
    expect(() => parseMcpServersConfig('{invalid')).toThrow(/JSON 格式错误/);
  });

  it('缺少 mcpServers 字段抛出错误（AC-020）', () => {
    expect(() => parseMcpServersConfig('{"servers": {}}')).toThrow(/必须包含 mcpServers/);
  });

  it('空 mcpServers 返回空数组', () => {
    const result = parseMcpServersConfig('{"mcpServers": {}}');
    expect(result).toEqual([]);
  });

  it('单个 Server 既无 command 也无 url 抛出错误（AC-021）', () => {
    expect(() => parseMcpServersConfig('{"mcpServers": {"bad": {}}}'))
      .toThrow(/bad.*配置无效/);
  });
});

describe('parseSingleServer 传输方式推断', () => {
  it('有 command 字段推断为 STDIO（AC-029）', () => {
    const req = parseSingleServer('fetch', { command: 'npx', args: ['mcp-fetch-server'] });
    expect(req.transport).toBe('STDIO');
    expect(req.command).toBe('npx');
    expect(req.args).toEqual(['mcp-fetch-server']);
    expect(req.enabled).toBe(true);
  });

  it('有 url 无 command 无 transport 推断为 HTTP（AC-030）', () => {
    const req = parseSingleServer('mermaid', { url: 'https://mcp.mermaid.ai/mcp' });
    expect(req.transport).toBe('HTTP');
    expect(req.url).toBe('https://mcp.mermaid.ai/mcp');
  });

  it('有 url + transport:"sse" 推断为 SSE（AC-031）', () => {
    const req = parseSingleServer('myserver', { url: 'https://example.com/sse', transport: 'sse' });
    expect(req.transport).toBe('SSE');
    expect(req.url).toBe('https://example.com/sse');
  });

  it('既无 command 也无 url 抛出错误（AC-021）', () => {
    expect(() => parseSingleServer('bad', {})).toThrow(/bad.*配置无效/);
  });

  it('headers 字段正确传递（AC-032）', () => {
    const req = parseSingleServer('myserver', {
      url: 'https://example.com',
      headers: { Authorization: 'Bearer xxx' },
    });
    expect(req.headers).toEqual({ Authorization: 'Bearer xxx' });
  });

  it('env 字段正确传递（AC-033）', () => {
    const req = parseSingleServer('fetch', {
      command: 'npx',
      env: { NODE_ENV: 'production' },
    });
    expect(req.env).toEqual({ NODE_ENV: 'production' });
  });

  it('stdio 无 args 时 args 默认为空数组', () => {
    const req = parseSingleServer('fetch', { command: 'npx' });
    expect(req.args).toEqual([]);
  });
});

describe('parseMcpServersConfig 批量解析（AC-011）', () => {
  it('解析包含多个 Server 的配置返回多个请求', () => {
    const jsonText = JSON.stringify({
      mcpServers: {
        fetch: { command: 'npx', args: ['mcp-fetch-server'] },
        mermaid: { url: 'https://mcp.mermaid.ai/mcp' },
      },
    });

    const result = parseMcpServersConfig(jsonText);
    expect(result).toHaveLength(2);
    const fetch = result.find((r) => r.name === 'fetch')!;
    const mermaid = result.find((r) => r.name === 'mermaid')!;
    expect(fetch.transport).toBe('STDIO');
    expect(mermaid.transport).toBe('HTTP');
  });

  it('完整示例配置解析为预期的请求数组', () => {
    const jsonText = `{
      "mcpServers": {
        "fetch": { "command": "npx", "args": ["mcp-fetch-server"] },
        "mermaid": { "url": "https://mcp.mermaid.ai/mcp" }
      }
    }`;

    const requests = parseMcpServersConfig(jsonText);
    expect(requests).toEqual<CreateMcpServerRequest[]>([
      {
        name: 'fetch',
        transport: 'STDIO',
        enabled: true,
        command: 'npx',
        args: ['mcp-fetch-server'],
        env: undefined,
      },
      {
        name: 'mermaid',
        transport: 'HTTP',
        enabled: true,
        url: 'https://mcp.mermaid.ai/mcp',
        headers: undefined,
      },
    ]);
  });
});
