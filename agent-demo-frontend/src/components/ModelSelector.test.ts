// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import ModelSelector from '@/components/ModelSelector.vue';
import type { LlmModel } from '@/types';

/**
 * ModelSelector 组件测试（Task-18）
 * 验证：渲染、选择、禁用态、分组、视图标记、空状态
 */

/** 辅助：构造测试用 chat 模型列表（跨厂商 + 视图标记） */
function createModels(): LlmModel[] {
  return [
    {
      id: 'model-1',
      vendorId: 'v1',
      vendorName: '火山引擎',
      modelName: 'doubao-seed-2.0-pro',
      displayName: '豆包Seed 2.0 Pro',
      type: 'chat',
      supportsVision: true,
    },
    {
      id: 'model-2',
      vendorId: 'v1',
      vendorName: '火山引擎',
      modelName: 'doubao-lite-2.0',
      displayName: '豆包Lite 2.0',
      type: 'chat',
      supportsVision: false,
    },
    {
      id: 'model-3',
      vendorId: 'v2',
      vendorName: 'OpenAI',
      modelName: 'gpt-4o',
      displayName: 'GPT-4o',
      type: 'chat',
      supportsVision: true,
    },
  ];
}

describe('ModelSelector', () => {
  it('modelValue 匹配模型时显示对应 displayName（选择态）', () => {
    const wrapper = mount(ModelSelector, {
      props: { modelValue: 'model-1', models: createModels() },
    });
    expect(wrapper.find('.model-trigger-name').text()).toContain('豆包Seed 2.0 Pro');
  });

  it('modelValue 无匹配时显示"选择模型"占位', () => {
    const wrapper = mount(ModelSelector, {
      props: { modelValue: '', models: createModels() },
    });
    expect(wrapper.find('.model-trigger-placeholder').exists()).toBe(true);
    expect(wrapper.find('.model-trigger-placeholder').text()).toBe('选择模型');
  });

  it('点击触发器展开下拉列表', async () => {
    const wrapper = mount(ModelSelector, {
      props: { modelValue: '', models: createModels() },
    });
    expect(wrapper.find('.model-dropdown').exists()).toBe(false);

    await wrapper.find('.model-trigger').trigger('click');
    expect(wrapper.find('.model-dropdown').exists()).toBe(true);
  });

  it('按厂商名称分组展示模型', async () => {
    const wrapper = mount(ModelSelector, {
      props: { modelValue: '', models: createModels() },
    });
    await wrapper.find('.model-trigger').trigger('click');

    const headers = wrapper.findAll('.model-group-header');
    expect(headers).toHaveLength(2);
    expect(headers[0].text()).toBe('火山引擎');
    expect(headers[1].text()).toBe('OpenAI');

    // 火山引擎组有 2 个模型，OpenAI 组有 1 个
    const options = wrapper.findAll('.model-option');
    expect(options).toHaveLength(3);
  });

  it('点击模型后 emit update:modelValue 并收起下拉', async () => {
    const wrapper = mount(ModelSelector, {
      props: { modelValue: '', models: createModels() },
    });
    await wrapper.find('.model-trigger').trigger('click');

    // 点击第二个选项（model-2）
    await wrapper.findAll('.model-option')[1].trigger('click');

    expect(wrapper.emitted('update:modelValue')).toBeTruthy();
    expect(wrapper.emitted('update:modelValue')![0]).toEqual(['model-2']);
    // 选中后收起下拉
    expect(wrapper.find('.model-dropdown').exists()).toBe(false);
  });

  it('supportsVision=true 的模型显示"支持视图"标记（AC 视图理解）', async () => {
    const wrapper = mount(ModelSelector, {
      props: { modelValue: '', models: createModels() },
    });
    await wrapper.find('.model-trigger').trigger('click');

    const options = wrapper.findAll('.model-option');
    // model-1（支持视图）
    expect(options[0].find('.model-option-vision').exists()).toBe(true);
    expect(options[0].find('.model-option-vision').text()).toBe('支持视图');
    // model-2（不支持视图）
    expect(options[1].find('.model-option-vision').exists()).toBe(false);
    // model-3（支持视图）
    expect(options[2].find('.model-option-vision').exists()).toBe(true);
  });

  it('当前选中模型为 supportsVision 时触发器显示"支持视图"标记', () => {
    const wrapper = mount(ModelSelector, {
      props: { modelValue: 'model-1', models: createModels() },
    });
    expect(wrapper.find('.model-vision-tag').exists()).toBe(true);
  });

  it('models 为空数组时下拉显示"暂无可用模型"提示', async () => {
    const wrapper = mount(ModelSelector, {
      props: { modelValue: '', models: [] },
    });
    await wrapper.find('.model-trigger').trigger('click');

    expect(wrapper.find('.model-empty').exists()).toBe(true);
    expect(wrapper.find('.model-empty').text()).toBe('暂无可用模型');
  });

  it('disabled 为 true 时置灰', () => {
    const wrapper = mount(ModelSelector, {
      props: { modelValue: '', models: createModels(), disabled: true },
    });
    expect(wrapper.find('.model-selector').classes()).toContain('disabled');
  });

  it('disabled 为 true 时点击触发器不展开下拉', async () => {
    const wrapper = mount(ModelSelector, {
      props: { modelValue: '', models: createModels(), disabled: true },
    });
    await wrapper.find('.model-trigger').trigger('click');
    expect(wrapper.find('.model-dropdown').exists()).toBe(false);
  });

  it('当前选中项有 selected 样式标记', async () => {
    const wrapper = mount(ModelSelector, {
      props: { modelValue: 'model-1', models: createModels() },
    });
    await wrapper.find('.model-trigger').trigger('click');

    const options = wrapper.findAll('.model-option');
    expect(options[0].classes()).toContain('selected');
    expect(options[1].classes()).not.toContain('selected');
  });
});
