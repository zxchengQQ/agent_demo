// @vitest-environment jsdom
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';

// Mock LLM API 避免真实请求
vi.mock('@/api/llm', () => ({
  getPredefinedVendors: vi.fn(),
  getVendors: vi.fn(),
  getModels: vi.fn(),
  getConfigStatus: vi.fn(),
  addVendor: vi.fn(),
  updateVendor: vi.fn(),
  deleteVendor: vi.fn(),
  testConnection: vi.fn(),
  syncConfig: vi.fn(),
}));

// Mock VendorEditDialog 隔离其内部逻辑（另建单测覆盖）
vi.mock('@/components/VendorEditDialog.vue', () => ({
  default: {
    name: 'VendorEditDialog',
    props: ['visible', 'vendor', 'predefinedVendors'],
    emits: ['close', 'saved'],
    template: `<div class="mock-vendor-dialog" v-if="visible">
      <button class="mock-close" @click="$emit('close')">close</button>
    </div>`,
  },
}));

import LlmConfigPage from '@/components/LlmConfigPage.vue';
import VendorCard from '@/components/VendorCard.vue';
import VendorEditDialog from '@/components/VendorEditDialog.vue';
import { useLlmStore } from '@/stores/llm';
import { getPredefinedVendors, getVendors, deleteVendor } from '@/api/llm';
import type { LlmVendor, PredefinedVendor } from '@/types';

/** 测试用厂商 */
const mockVendor: LlmVendor = {
  id: 'vendor-001',
  name: '火山引擎',
  type: 'predefined',
  baseUrl: 'https://ark.cn-beijing.volces.com/api/v3',
  apiKeyMasked: 'sk-****key',
  apiKeyConfigured: true,
  thinkingTrigger: 'enabled',
  timeout: 60000,
  maxRetries: 3,
  temperature: 0.7,
  models: [],
};

/** 测试用预定义厂商 */
const mockPredefined: PredefinedVendor = {
  code: 'volc',
  name: '火山引擎',
  baseUrl: 'https://ark.cn-beijing.volces.com/api/v3',
  thinkingTrigger: 'enabled',
  models: [],
};

describe('LlmConfigPage', () => {
  let pinia: Pinia;

  beforeEach(() => {
    pinia = createPinia();
    setActivePinia(pinia);
    vi.clearAllMocks();
    localStorage.clear();
  });

  it('页面加载时调用 loadVendors（经 getVendors）', async () => {
    vi.mocked(getVendors).mockResolvedValue([mockVendor]);
    vi.mocked(getPredefinedVendors).mockResolvedValue([mockPredefined]);

    mount(LlmConfigPage, { global: { plugins: [pinia] } });
    await flushPromises();

    expect(getVendors).toHaveBeenCalled();
  });

  it('页面加载时加载预定义厂商目录', async () => {
    vi.mocked(getVendors).mockResolvedValue([]);
    vi.mocked(getPredefinedVendors).mockResolvedValue([mockPredefined]);

    mount(LlmConfigPage, { global: { plugins: [pinia] } });
    await flushPromises();

    expect(getPredefinedVendors).toHaveBeenCalled();
  });

  it('有厂商时渲染 VendorCard 卡片列表', async () => {
    vi.mocked(getVendors).mockResolvedValue([mockVendor]);
    vi.mocked(getPredefinedVendors).mockResolvedValue([]);

    const wrapper = mount(LlmConfigPage, { global: { plugins: [pinia] } });
    await flushPromises();

    expect(wrapper.findAllComponents(VendorCard)).toHaveLength(1);
    expect(wrapper.find('.empty-state').exists()).toBe(false);
  });

  it('无厂商时显示空状态引导 + 添加厂商按钮', async () => {
    vi.mocked(getVendors).mockResolvedValue([]);
    vi.mocked(getPredefinedVendors).mockResolvedValue([]);

    const wrapper = mount(LlmConfigPage, { global: { plugins: [pinia] } });
    await flushPromises();

    expect(wrapper.find('.empty-state').exists()).toBe(true);
    expect(wrapper.find('.empty-text').text()).toContain('暂无已配置厂商');
    expect(wrapper.find('.btn-add-empty').exists()).toBe(true);
  });

  it('顶部"添加厂商"按钮打开 VendorEditDialog（新增模式）', async () => {
    vi.mocked(getVendors).mockResolvedValue([]);
    vi.mocked(getPredefinedVendors).mockResolvedValue([]);

    const wrapper = mount(LlmConfigPage, { global: { plugins: [pinia] } });
    await flushPromises();

    const dialog = wrapper.findComponent(VendorEditDialog);
    expect(dialog.props('visible')).toBe(false);

    await wrapper.find('.btn-add').trigger('click');
    expect(dialog.props('visible')).toBe(true);
    expect(dialog.props('vendor')).toBeNull();
  });

  it('点击 VendorCard 的 edit 事件打开 VendorEditDialog（编辑模式）', async () => {
    vi.mocked(getVendors).mockResolvedValue([mockVendor]);
    vi.mocked(getPredefinedVendors).mockResolvedValue([]);

    const wrapper = mount(LlmConfigPage, { global: { plugins: [pinia] } });
    await flushPromises();

    // 触发第一个 VendorCard 的 edit 事件
    await wrapper.findComponent(VendorCard).vm.$emit('edit', mockVendor);

    const dialog = wrapper.findComponent(VendorEditDialog);
    expect(dialog.props('visible')).toBe(true);
    expect(dialog.props('vendor')).toEqual(mockVendor);
  });

  it('点击 VendorCard 的 delete 事件调用 llmStore.deleteVendor', async () => {
    vi.mocked(getVendors).mockResolvedValue([mockVendor]);
    vi.mocked(getPredefinedVendors).mockResolvedValue([]);
    vi.mocked(deleteVendor).mockResolvedValue(undefined);

    const wrapper = mount(LlmConfigPage, { global: { plugins: [pinia] } });
    await flushPromises();

    await wrapper.findComponent(VendorCard).vm.$emit('delete', mockVendor);
    await flushPromises();

    expect(deleteVendor).toHaveBeenCalledWith('vendor-001');
  });

  it('VendorEditDialog 的 saved 事件后关闭弹窗并刷新厂商列表', async () => {
    vi.mocked(getVendors).mockResolvedValue([mockVendor]);
    vi.mocked(getPredefinedVendors).mockResolvedValue([]);

    const wrapper = mount(LlmConfigPage, { global: { plugins: [pinia] } });
    await flushPromises();

    // 打开新增弹窗
    await wrapper.find('.btn-add').trigger('click');
    expect(wrapper.findComponent(VendorEditDialog).props('visible')).toBe(true);

    // 触发 saved
    await wrapper.findComponent(VendorEditDialog).vm.$emit('saved');
    await flushPromises();

    expect(wrapper.findComponent(VendorEditDialog).props('visible')).toBe(false);
  });
});
