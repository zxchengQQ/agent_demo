import type { CreateMcpServerRequest, McpTransportType } from '@/types';

/**
 * MCP Server JSON 配置解析工具
 *
 * 业务含义：将用户粘贴的业界通用 mcpServers JSON 配置（与 Claude Desktop / Cursor 一致）
 * 解析为后端可接收的 CreateMcpServerRequest[] 请求数组。
 * 传输方式自动推断：有 command -> STDIO；有 transport:"sse" -> SSE；有 url -> HTTP（默认）。
 *
 * 格式示例：
 * {
 *   "mcpServers": {
 *     "fetch": { "command": "npx", "args": ["mcp-fetch-server"] },
 *     "mermaid": { "url": "https://mcp.mermaid.ai/mcp" },
 *     "myserver": { "url": "https://example.com/sse", "transport": "sse" }
 *   }
 * }
 */

/** 顶层配置结构 */
interface McpServersConfig {
  mcpServers: Record<string, Record<string, unknown>>;
}

/**
 * 解析 mcpServers JSON 配置为请求数组
 *
 * @param jsonText 用户输入的 JSON 文本
 * @returns CreateMcpServerRequest[] 请求数组
 * @throws Error 当 JSON 格式非法、缺少 mcpServers 字段、Server 配置缺少必要字段时
 */
export function parseMcpServersConfig(jsonText: string): CreateMcpServerRequest[] {
  // 1. 校验 JSON 格式（AC-019）
  let config: unknown;
  try {
    config = JSON.parse(jsonText);
  } catch (e) {
    throw new Error(`JSON 格式错误：${(e as Error).message}`);
  }

  // 2. 校验顶层结构（AC-020）
  const mcpConfig = config as McpServersConfig;
  if (!mcpConfig || typeof mcpConfig !== 'object' || !mcpConfig.mcpServers) {
    throw new Error('配置必须包含 mcpServers 字段');
  }

  // 3. 遍历每个 Server 条目
  const requests: CreateMcpServerRequest[] = [];
  for (const [name, serverConfig] of Object.entries(mcpConfig.mcpServers)) {
    requests.push(parseSingleServer(name, serverConfig));
  }

  return requests;
}

/**
 * 解析单个 Server 配置，推断传输方式并映射字段
 *
 * @param name Server 名称（mcpServers 的 key）
 * @param serverConfig Server 配置对象
 * @returns CreateMcpServerRequest 请求对象
 * @throws Error 当配置既无 command 也无 url 时（AC-021）
 */
export function parseSingleServer(
  name: string,
  serverConfig: Record<string, unknown>,
): CreateMcpServerRequest {
  // 业务含义：推断传输方式，若配置无效（无 command 也无 url），
  // 抛出带 Server 名称的错误消息，便于用户在批量配置中定位问题（AC-021）。
  let transport: McpTransportType;
  try {
    transport = inferTransport(serverConfig);
  } catch (e) {
    throw new Error(`Server '${name}' ${(e as Error).message}`);
  }

  const request: CreateMcpServerRequest = {
    name,
    transport,
    enabled: true,
  };

  // 根据传输方式填充对应字段（AC-029/030/031）
  if (transport === 'STDIO') {
    request.command = serverConfig.command as string;
    request.args = (serverConfig.args as string[]) ?? [];
    request.env = (serverConfig.env as Record<string, string>) ?? undefined;
  } else {
    request.url = serverConfig.url as string;
    request.headers = (serverConfig.headers as Record<string, string>) ?? undefined;
  }

  return request;
}

/**
 * 推断传输方式（AC-029/030/031）
 * 规则：
 * 1. 有 command 字段 -> STDIO
 * 2. 有 transport:"sse" 字段 -> SSE
 * 3. 有 url 字段 -> HTTP（默认）
 * 4. 否则抛出错误（AC-021）
 */
function inferTransport(serverConfig: Record<string, unknown>): McpTransportType {
  if ('command' in serverConfig) {
    return 'STDIO';
  }
  if (serverConfig.transport === 'sse') {
    return 'SSE';
  }
  if ('url' in serverConfig) {
    return 'HTTP';
  }
  throw new Error('配置无效：必须包含 command（stdio）或 url（sse/http）字段');
}
