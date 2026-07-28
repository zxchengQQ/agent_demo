// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import KnowledgeBaseSelector from '@/components/KnowledgeBaseSelector.vue';
import type { KnowledgeBase } from '@/types';

/**
 * KnowledgeBaseSelector 组件测试（Task-07）
 * 验证标准来源：Task-07 验证标准
 * 关联 AC：AC-011、AC-014、AC-028、AC-029
 */

/** 辅助：构造测试用知识库列表 */
function createKnowledgeBases(): KnowledgeBase[] {
  return [
    { id: '1', name: '产品手册', description: '产品说明文档', documentCount: 5, createTime: '2025-01-01T00:00:00Z' },
    { id: '2', name: '常见问题', description: 'FAQ 文档', documentCount: 3, createTime: '2025-01-02T00:00:00Z' },
  ];
}

describe('KnowledgeBaseSelector', () => {
  it('modelValue 为空数组时显示"自动"标签（AC-028）', () => {
    const wrapper = mount(KnowledgeBaseSelector, {
      props: { modelValue: [], knowledgeBases: createKnowledgeBases() },
    });
    expect(wrapper.find('.kb-tag-auto').exists()).toBe(true);
    expect(wrapper.find('.kb-tag-auto').text()).toBe('自动');
  });

  it('modelValue 为单选时显示对应知识库名称标签（AC-029）', () => {
    const wrapper = mount(KnowledgeBaseSelector, {
      props: { modelValue: ['产品手册'], knowledgeBases: createKnowledgeBases() },
    });
    // 不应显示"自动"标签
    expect(wrapper.find('.kb-tag-auto').exists()).toBe(false);
    // 应显示选中的知识库名称
    const tags = wrapper.findAll('.kb-tag');
    expect(tags).toHaveLength(1);
    expect(tags[0].text()).toBe('产品手册');
  });

  it('modelValue 为多选时显示多个标签（AC-029）', () => {
    const wrapper = mount(KnowledgeBaseSelector, {
      props: { modelValue: ['产品手册', '常见问题'], knowledgeBases: createKnowledgeBases() },
    });
    const tags = wrapper.findAll('.kb-tag');
    expect(tags).toHaveLength(2);
    expect(tags[0].text()).toBe('产品手册');
    expect(tags[1].text()).toBe('常见问题');
  });

  it('点击触发器展开知识库下拉列表，点击某项切换选中（AC-014）', async () => {
    const wrapper = mount(KnowledgeBaseSelector, {
      props: { modelValue: [], knowledgeBases: createKnowledgeBases() },
    });
    // 初始下拉关闭
    expect(wrapper.find('.kb-dropdown').exists()).toBe(false);

    // 点击触发器展开
    await wrapper.find('.kb-trigger').trigger('click');
    expect(wrapper.find('.kb-dropdown').exists()).toBe(true);

    // 下拉列表展示所有知识库
    const options = wrapper.findAll('.kb-option');
    expect(options).toHaveLength(2);
    expect(options[0].text()).toContain('产品手册');
    expect(options[1].text()).toContain('常见问题');
  });

  it('点击未选中的知识库项后 emit update:modelValue 添加该项（AC-029）', async () => {
    const wrapper = mount(KnowledgeBaseSelector, {
      props: { modelValue: [], knowledgeBases: createKnowledgeBases() },
    });
    await wrapper.find('.kb-trigger').trigger('click');

    // 点击第一个选项（产品手册，未选中 -> 选中）
    await wrapper.findAll('.kb-option')[0].trigger('click');

    expect(wrapper.emitted('update:modelValue')).toBeTruthy();
    expect(wrapper.emitted('update:modelValue')![0]).toEqual([['产品手册']]);
  });

  it('点击已选中的知识库项后 emit update:modelValue 移除该项（AC-029）', async () => {
    const wrapper = mount(KnowledgeBaseSelector, {
      props: { modelValue: ['产品手册'], knowledgeBases: createKnowledgeBases() },
    });
    await wrapper.find('.kb-trigger').trigger('click');

    // 点击第一个选项（产品手册，已选中 -> 取消选中）
    await wrapper.findAll('.kb-option')[0].trigger('click');

    expect(wrapper.emitted('update:modelValue')).toBeTruthy();
    // 取消后为空数组（回到自动模式）
    expect(wrapper.emitted('update:modelValue')![0]).toEqual([[]]);
  });

  it('knowledgeBases 为空数组时下拉显示"暂无知识库"提示（AC-028）', async () => {
    const wrapper = mount(KnowledgeBaseSelector, {
      props: { modelValue: [], knowledgeBases: [] },
    });
    await wrapper.find('.kb-trigger').trigger('click');

    expect(wrapper.find('.kb-empty').exists()).toBe(true);
    expect(wrapper.find('.kb-empty').text()).toContain('暂无知识库');
  });

  it('disabled 为 true 时置灰不可点击（AC-011）', () => {
    const wrapper = mount(KnowledgeBaseSelector, {
      props: { modelValue: [], knowledgeBases: createKnowledgeBases(), disabled: true },
    });
    expect(wrapper.find('.kb-selector').classes()).toContain('disabled');
  });

  it('disabled 为 true 时点击触发器不展开下拉', async () => {
    const wrapper = mount(KnowledgeBaseSelector, {
      props: { modelValue: [], knowledgeBases: createKnowledgeBases(), disabled: true },
    });
    await wrapper.find('.kb-trigger').trigger('click');
    expect(wrapper.find('.kb-dropdown').exists()).toBe(false);
  });

  it('已选中的知识库项有 selected 样式标记（AC-014）', async () => {
    const wrapper = mount(KnowledgeBaseSelector, {
      props: { modelValue: ['产品手册'], knowledgeBases: createKnowledgeBases() },
    });
    await wrapper.find('.kb-trigger').trigger('click');

    const options = wrapper.findAll('.kb-option');
    expect(options[0].classes()).toContain('selected');
    expect(options[1].classes()).not.toContain('selected');
  });
});
