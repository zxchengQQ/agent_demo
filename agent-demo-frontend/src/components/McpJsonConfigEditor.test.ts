import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import McpJsonConfigEditor from './McpJsonConfigEditor.vue';
import * as mcpApi from '@/api/mcp';
import type { CreateMcpServerRequest } from '@/types';

/**
 * McpJsonConfigEditor 组件测试（Task-06）
 * 验证标准来源：Task-06 验证标准
 * 关联 AC：AC-007, AC-008, AC-009, AC-010, AC-011, AC-019, AC-020, AC-021,
 *          AC-022, AC-023, AC-024, AC-028, AC-029, AC-030, AC-031
 */

vi.mock('@/api/mcp', () => ({
  addServer: vi.fn(),
}));

const mockedAddServer = vi.mocked(mcpApi.addServer);

function mountEditor() {
  return mount(McpJsonConfigEditor, { props: { visible: true } });
}

async function setJson(wrapper: ReturnType<typeof mountEditor>, text: string) {
  await wrapper.find('textarea').setValue(text);
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('McpJsonConfigEditor 弹窗基础', () => {
  it('visible=true 时渲染弹窗', () => {
    const wrapper = mountEditor();
    expect(wrapper.find('.editor-dialog').exists()).toBe(true);
  });

  it('visible=false 时不渲染弹窗', () => {
    const wrapper = mount(McpJsonConfigEditor, { props: { visible: false } });
    expect(wrapper.find('.editor-dialog').exists()).toBe(false);
  });

  it('点击关闭按钮 emit close 事件', async () => {
    const wrapper = mountEditor();
    await wrapper.find('.editor-close').trigger('click');
    expect(wrapper.emitted('close')).toHaveLength(1);
  });

  it('点击遮罩关闭 emit close 事件', async () => {
    const wrapper = mountEditor();
    await wrapper.find('.editor-overlay').trigger('click');
    // click.self 修饰符：点击遮罩本身才触发
    expect(wrapper.emitted('close')).toHaveLength(1);
  });
});

describe('McpJsonConfigEditor 校验与错误', () => {
  it('非法 JSON 显示 JSON 格式错误（AC-019）', async () => {
    const wrapper = mountEditor();
    await setJson(wrapper, '{invalid');
    await wrapper.find('.btn-save').trigger('click');
    await flushPromises();
    expect(wrapper.find('.editor-error').text()).toContain('JSON 格式错误');
    expect(mockedAddServer).not.toHaveBeenCalled();
  });

  it('缺少 mcpServers 字段显示错误（AC-020）', async () => {
    const wrapper = mountEditor();
    await setJson(wrapper, '{"servers": {}}');
    await wrapper.find('.btn-save').trigger('click');
    await flushPromises();
    expect(wrapper.find('.editor-error').text()).toContain('必须包含 mcpServers');
    expect(mockedAddServer).not.toHaveBeenCalled();
  });

  it('Server 缺少必要字段显示错误（AC-021）', async () => {
    const wrapper = mountEditor();
    await setJson(wrapper, '{"mcpServers": {"bad": {}}}');
    await wrapper.find('.btn-save').trigger('click');
    await flushPromises();
    expect(wrapper.find('.editor-error').text()).toContain("bad");
    expect(wrapper.find('.editor-error').text()).toContain('配置无效');
    expect(mockedAddServer).not.toHaveBeenCalled();
  });
});

describe('McpJsonConfigEditor 添加成功', () => {
  it('stdio 配置添加成功并 emit saved（AC-008, AC-029）', async () => {
    mockedAddServer.mockResolvedValue({} as never);
    const wrapper = mountEditor();
    await setJson(wrapper, '{"mcpServers": {"fetch": {"command": "npx", "args": ["mcp-fetch-server"]}}}');
    await wrapper.find('.btn-save').trigger('click');
    await flushPromises();

    const expected: CreateMcpServerRequest = {
      name: 'fetch', transport: 'STDIO', enabled: true, command: 'npx', args: ['mcp-fetch-server'],
    };
    expect(mockedAddServer).toHaveBeenCalledWith(expect.objectContaining({ name: 'fetch', transport: 'STDIO', command: 'npx' }));
    expect(wrapper.emitted('saved')).toHaveLength(1);
  });

  it('http 配置添加成功（AC-009, AC-030）', async () => {
    mockedAddServer.mockResolvedValue({} as never);
    const wrapper = mountEditor();
    await setJson(wrapper, '{"mcpServers": {"mermaid": {"url": "https://mcp.mermaid.ai/mcp"}}}');
    await wrapper.find('.btn-save').trigger('click');
    await flushPromises();
    expect(mockedAddServer).toHaveBeenCalledWith(expect.objectContaining({ name: 'mermaid', transport: 'HTTP', url: 'https://mcp.mermaid.ai/mcp' }));
    expect(wrapper.emitted('saved')).toHaveLength(1);
  });

  it('sse 配置添加成功（AC-010, AC-031）', async () => {
    mockedAddServer.mockResolvedValue({} as never);
    const wrapper = mountEditor();
    await setJson(wrapper, '{"mcpServers": {"myserver": {"url": "https://example.com/sse", "transport": "sse"}}}');
    await wrapper.find('.btn-save').trigger('click');
    await flushPromises();
    expect(mockedAddServer).toHaveBeenCalledWith(expect.objectContaining({ name: 'myserver', transport: 'SSE' }));
    expect(wrapper.emitted('saved')).toHaveLength(1);
  });

  it('批量添加多个 Server 全部成功时 emit saved（AC-011）', async () => {
    mockedAddServer.mockResolvedValue({} as never);
    const wrapper = mountEditor();
    await setJson(wrapper, '{"mcpServers": {"fetch": {"command": "npx"}, "mermaid": {"url": "https://mcp.mermaid.ai/mcp"}}}');
    await wrapper.find('.btn-save').trigger('click');
    await flushPromises();
    expect(mockedAddServer).toHaveBeenCalledTimes(2);
    expect(wrapper.emitted('saved')).toHaveLength(1);
  });
});

describe('McpJsonConfigEditor 部分失败（AC-024）', () => {
  it('1 个成功 1 个失败时显示结果列表且不 emit saved', async () => {
    mockedAddServer
      .mockResolvedValueOnce({} as never)
      .mockRejectedValueOnce(new Error('MCP Server 名称已存在'));
    const wrapper = mountEditor();
    await setJson(wrapper, '{"mcpServers": {"ok": {"command": "npx"}, "dup": {"command": "npx"}}}');
    await wrapper.find('.btn-save').trigger('click');
    await flushPromises();

    const items = wrapper.findAll('.editor-result-item');
    expect(items).toHaveLength(2);
    expect(items[0].classes()).toContain('result-success');
    expect(items[1].classes()).toContain('result-fail');
    expect(items[1].text()).toContain('MCP Server 名称已存在');
    expect(wrapper.emitted('saved')).toBeUndefined();
    // 弹窗保持打开（显示关闭按钮）
    expect(wrapper.find('.btn-cancel').exists()).toBe(true);
  });

  it('全部失败时显示所有失败结果', async () => {
    mockedAddServer.mockRejectedValue(new Error('连接失败'));
    const wrapper = mountEditor();
    await setJson(wrapper, '{"mcpServers": {"a": {"command": "npx"}, "b": {"command": "npx"}}}');
    await wrapper.find('.btn-save').trigger('click');
    await flushPromises();

    const items = wrapper.findAll('.editor-result-item');
    expect(items).toHaveLength(2);
    expect(items.every((i) => i.classes().includes('result-fail'))).toBe(true);
    expect(wrapper.emitted('saved')).toBeUndefined();
  });
});

describe('McpJsonConfigEditor 防重复提交（AC-028）', () => {
  it('提交过程中按钮禁用', async () => {
    let resolveAdd: (v: never) => void = () => {};
    mockedAddServer.mockImplementationOnce(() => new Promise((resolve) => { resolveAdd = resolve; }));
    const wrapper = mountEditor();
    await setJson(wrapper, '{"mcpServers": {"fetch": {"command": "npx"}}}');
    await wrapper.find('.btn-save').trigger('click');
    await wrapper.find('.btn-save').trigger('click');
    // 两次点击但只调用一次 addServer
    expect(mockedAddServer).toHaveBeenCalledTimes(1);
    resolveAdd({} as never);
    await flushPromises();
  });
});
