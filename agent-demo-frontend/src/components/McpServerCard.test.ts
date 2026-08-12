import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import McpServerCard from './McpServerCard.vue';
import type { McpServerInfo, McpToolInfo } from '@/types';

/**
 * McpServerCard 组件测试（Task-05）
 * 验证标准来源：Task-05 验证标准
 * 关联 AC：AC-005, AC-013, AC-015, AC-017, AC-018, AC-036
 */

/** 构造测试用 McpServerInfo */
function makeServer(overrides: Partial<McpServerInfo> = {}): McpServerInfo {
  return {
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
    ...overrides,
  };
}

beforeEach(() => {
  vi.restoreAllMocks();
  vi.stubGlobal('confirm', vi.fn(() => true));
});

describe('McpServerCard 状态徽章', () => {
  it('CONNECTED 状态显示 "已连接"', () => {
    const wrapper = mount(McpServerCard, {
      props: { server: makeServer(), tools: null },
    });
    expect(wrapper.find('.mcp-status').text()).toBe('已连接');
    expect(wrapper.find('.mcp-status').classes()).toContain('st-connected');
  });

  it('DISCONNECTED 状态显示 "已断线"', () => {
    const wrapper = mount(McpServerCard, {
      props: { server: makeServer({ status: 'DISCONNECTED' }), tools: null },
    });
    expect(wrapper.find('.mcp-status').text()).toBe('已断线');
    expect(wrapper.find('.mcp-status').classes()).toContain('st-disconnected');
  });

  it('ERROR 状态显示 "错误"', () => {
    const wrapper = mount(McpServerCard, {
      props: { server: makeServer({ status: 'ERROR' }), tools: null },
    });
    expect(wrapper.find('.mcp-status').text()).toBe('错误');
    expect(wrapper.find('.mcp-status').classes()).toContain('st-error');
  });

  it('DISABLED 状态显示 "已禁用"', () => {
    const wrapper = mount(McpServerCard, {
      props: { server: makeServer({ status: 'DISABLED' }), tools: null },
    });
    expect(wrapper.find('.mcp-status').text()).toBe('已禁用');
    expect(wrapper.find('.mcp-status').classes()).toContain('st-disabled');
  });
});

describe('McpServerCard 地址显示（AC-036）', () => {
  it('stdio 类型显示 command + args', () => {
    const wrapper = mount(McpServerCard, {
      props: { server: makeServer(), tools: null },
    });
    expect(wrapper.find('.mcp-address').text()).toBe('npx mcp-fetch-server');
  });

  it('stdio 类型无 args 时仅显示 command', () => {
    const wrapper = mount(McpServerCard, {
      props: { server: makeServer({ args: null }), tools: null },
    });
    expect(wrapper.find('.mcp-address').text()).toBe('npx');
  });

  it('http 类型显示 url', () => {
    const wrapper = mount(McpServerCard, {
      props: {
        server: makeServer({ transport: 'HTTP', url: 'https://mcp.mermaid.ai/mcp', command: null, args: null }),
        tools: null,
      },
    });
    expect(wrapper.find('.mcp-address').text()).toBe('https://mcp.mermaid.ai/mcp');
  });

  it('无地址时显示占位符', () => {
    const wrapper = mount(McpServerCard, {
      props: {
        server: makeServer({ transport: 'HTTP', url: null, command: null, args: null }),
        tools: null,
      },
    });
    expect(wrapper.find('.mcp-address').text()).toBe('—');
  });
});

describe('McpServerCard 工具数量与展开（AC-017, AC-018）', () => {
  it('显示工具数量文本', () => {
    const wrapper = mount(McpServerCard, {
      props: { server: makeServer({ toolCount: 3 }), tools: null },
    });
    expect(wrapper.find('.mcp-tools').text()).toContain('3 个工具');
  });

  it('点击工具数量 emit toggleTools 事件', async () => {
    const wrapper = mount(McpServerCard, {
      props: { server: makeServer({ toolCount: 3 }), tools: null },
    });
    await wrapper.find('.mcp-tools').trigger('click');
    expect(wrapper.emitted('toggleTools')).toHaveLength(1);
    expect(wrapper.emitted('toggleTools')![0]).toEqual(['fetch']);
  });

  it('tools 非 null 时展开显示工具详情', () => {
    const tools: McpToolInfo[] = [
      { originalName: 'fetch', registeredName: 'mcp_fetch_fetch', description: '抓取网页', parametersSchema: '{"type":"object"}' },
    ];
    const wrapper = mount(McpServerCard, {
      props: { server: makeServer(), tools },
    });
    expect(wrapper.find('.mcp-tools-list').exists()).toBe(true);
    expect(wrapper.find('.mcp-tool-name').text()).toBe('fetch');
    expect(wrapper.find('.mcp-tool-registered').text()).toBe('mcp_fetch_fetch');
  });

  it('tools 为空数组时显示空状态', () => {
    const wrapper = mount(McpServerCard, {
      props: { server: makeServer({ toolCount: 0 }), tools: [] },
    });
    expect(wrapper.find('.mcp-tools-empty').exists()).toBe(true);
    expect(wrapper.find('.mcp-tools-empty').text()).toBe('该 Server 暂无工具');
  });

  it('loadingTools 为 true 时显示加载中', () => {
    const wrapper = mount(McpServerCard, {
      props: { server: makeServer(), tools: [], loadingTools: true },
    });
    expect(wrapper.find('.mcp-tools-loading').exists()).toBe(true);
    expect(wrapper.find('.mcp-tools-loading').text()).toBe('加载中...');
  });
});

describe('McpServerCard 重连按钮（AC-015）', () => {
  it('CONNECTED 状态重连按钮禁用并显示 "已连接"', () => {
    const wrapper = mount(McpServerCard, {
      props: { server: makeServer(), tools: null },
    });
    const btn = wrapper.find('.btn-reconnect');
    expect(btn.attributes('disabled')).toBeDefined();
    expect(btn.text()).toBe('已连接');
  });

  it('DISCONNECTED 状态重连按钮可点击并显示 "重连"', () => {
    const wrapper = mount(McpServerCard, {
      props: { server: makeServer({ status: 'DISCONNECTED' }), tools: null },
    });
    const btn = wrapper.find('.btn-reconnect');
    expect(btn.attributes('disabled')).toBeUndefined();
    expect(btn.text()).toBe('重连');
  });

  it('点击重连 emit reconnect 事件', async () => {
    const wrapper = mount(McpServerCard, {
      props: { server: makeServer({ status: 'DISCONNECTED' }), tools: null },
    });
    await wrapper.find('.btn-reconnect').trigger('click');
    expect(wrapper.emitted('reconnect')).toHaveLength(1);
    expect(wrapper.emitted('reconnect')![0]).toEqual(['fetch']);
  });
});

describe('McpServerCard 删除（AC-013）', () => {
  it('删除需二次确认，确认后 emit delete 事件', async () => {
    const confirmSpy = vi.fn(() => true);
    vi.stubGlobal('confirm', confirmSpy);
    const wrapper = mount(McpServerCard, {
      props: { server: makeServer(), tools: null },
    });
    await wrapper.find('.btn-delete').trigger('click');
    expect(confirmSpy).toHaveBeenCalled();
    expect(confirmSpy.mock.calls[0][0]).toContain('将断开连接并注销所有工具');
    expect(wrapper.emitted('delete')).toHaveLength(1);
    expect(wrapper.emitted('delete')![0]).toEqual(['fetch']);
  });

  it('取消确认时不 emit delete 事件', async () => {
    vi.stubGlobal('confirm', vi.fn(() => false));
    const wrapper = mount(McpServerCard, {
      props: { server: makeServer(), tools: null },
    });
    await wrapper.find('.btn-delete').trigger('click');
    expect(wrapper.emitted('delete')).toBeUndefined();
  });
});

describe('McpServerCard 错误信息', () => {
  it('DISCONNECTED 状态有 lastError 时显示错误信息', () => {
    const wrapper = mount(McpServerCard, {
      props: {
        server: makeServer({ status: 'DISCONNECTED', lastError: '连接断开' }),
        tools: null,
      },
    });
    expect(wrapper.find('.mcp-error').text()).toBe('连接断开');
  });
});
