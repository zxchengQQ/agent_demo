// @vitest-environment jsdom
import { describe, it, expect, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import VendorCard from '@/components/VendorCard.vue';
import type { LlmVendor } from '@/types';

/**
 * VendorCard 组件测试（Task-19）
 * 验证：厂商信息展示、类型标签、Base URL 截断、模型摘要、API Key 状态、编辑/删除
 */

/** 辅助：构造测试用厂商 */
function makeVendor(overrides: Partial<LlmVendor> = {}): LlmVendor {
  return {
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
    models: [
      { id: 'm1', vendorId: 'vendor-001', vendorName: '火山引擎', modelName: 'a', displayName: 'A', type: 'chat', supportsVision: true },
      { id: 'm2', vendorId: 'vendor-001', vendorName: '火山引擎', modelName: 'b', displayName: 'B', type: 'embedding', supportsVision: false },
    ],
    ...overrides,
  };
}

describe('VendorCard', () => {
  it('渲染厂商名称', () => {
    const wrapper = mount(VendorCard, { props: { vendor: makeVendor() } });
    expect(wrapper.find('.vendor-name').text()).toBe('火山引擎');
  });

  it('预定义厂商显示"预定义"类型标签', () => {
    const wrapper = mount(VendorCard, { props: { vendor: makeVendor() } });
    expect(wrapper.find('.vendor-type').text()).toBe('预定义');
    expect(wrapper.find('.vendor-type').classes()).toContain('type-predefined');
  });

  it('自定义厂商显示"自定义"类型标签', () => {
    const wrapper = mount(VendorCard, { props: { vendor: makeVendor({ type: 'custom' }) } });
    expect(wrapper.find('.vendor-type').text()).toBe('自定义');
    expect(wrapper.find('.vendor-type').classes()).toContain('type-custom');
  });

  it('渲染 Base URL', () => {
    const wrapper = mount(VendorCard, { props: { vendor: makeVendor() } });
    expect(wrapper.find('.vendor-url').text()).toBe('https://ark.cn-beijing.volces.com/api/v3');
  });

  it('模型摘要显示各类型数量（对话 + 向量化）', () => {
    const wrapper = mount(VendorCard, { props: { vendor: makeVendor() } });
    expect(wrapper.find('.vendor-models').text()).toContain('1 个对话');
    expect(wrapper.find('.vendor-models').text()).toContain('1 个向量化');
  });

  it('无模型时模型摘要显示"未配置模型"', () => {
    const wrapper = mount(VendorCard, { props: { vendor: makeVendor({ models: [] }) } });
    expect(wrapper.find('.vendor-models').text()).toBe('未配置模型');
  });

  it('apiKeyConfigured 为 true 时显示"已配置"且有 configured 样式', () => {
    const wrapper = mount(VendorCard, { props: { vendor: makeVendor() } });
    expect(wrapper.find('.vendor-api-key').text()).toBe('已配置');
    expect(wrapper.find('.vendor-api-key').classes()).toContain('configured');
  });

  it('apiKeyConfigured 为 false 时显示"未配置"且无 configured 样式', () => {
    const wrapper = mount(VendorCard, { props: { vendor: makeVendor({ apiKeyConfigured: false }) } });
    expect(wrapper.find('.vendor-api-key').text()).toBe('未配置');
    expect(wrapper.find('.vendor-api-key').classes()).not.toContain('configured');
  });

  it('点击"编辑"按钮 emit edit 事件携带厂商', async () => {
    const vendor = makeVendor();
    const wrapper = mount(VendorCard, { props: { vendor } });
    await wrapper.find('.btn-edit').trigger('click');
    expect(wrapper.emitted('edit')).toBeTruthy();
    expect(wrapper.emitted('edit')![0]).toEqual([vendor]);
  });

  it('点击"删除"按钮且确认后 emit delete 事件携带厂商', async () => {
    const vendor = makeVendor();
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
    const wrapper = mount(VendorCard, { props: { vendor } });

    await wrapper.find('.btn-delete').trigger('click');
    expect(confirmSpy).toHaveBeenCalled();
    expect(wrapper.emitted('delete')).toBeTruthy();
    expect(wrapper.emitted('delete')![0]).toEqual([vendor]);
  });

  it('点击"删除"按钮但取消确认时不 emit delete', async () => {
    const vendor = makeVendor();
    vi.spyOn(window, 'confirm').mockReturnValue(false);
    const wrapper = mount(VendorCard, { props: { vendor } });

    await wrapper.find('.btn-delete').trigger('click');
    expect(wrapper.emitted('delete')).toBeFalsy();
  });
});
