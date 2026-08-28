// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import SkillSelector from '@/components/SkillSelector.vue';
import type { SkillInfo } from '@/types';

/**
 * SkillSelector 组件测试（agent-skill Task-20）
 * 验证标准来源：Task-20 验证标准
 * 关联 AC：AC-N03（手动指定入口）、AC-M03（会话隔离）
 */

/** 辅助：构造测试用技能列表 */
function createSkills(): SkillInfo[] {
  return [
    { id: 'weekly-report-expert', name: '周报撰写专家', description: '编写专业周报', instruction: '指令', resources: [], scripts: [], enabled: true, source: 'PRESET' },
    { id: 'data-query-assistant', name: '数据查询助手', description: '查询数据', instruction: '指令', resources: [], scripts: [], enabled: true, source: 'PRESET' },
  ];
}

describe('SkillSelector', () => {
  it('modelValue 为空数组时显示"自动"标签（自动模式）', () => {
    const wrapper = mount(SkillSelector, {
      props: { modelValue: [], skills: createSkills() },
    });
    expect(wrapper.find('.skill-tag-auto').exists()).toBe(true);
    expect(wrapper.find('.skill-tag-auto').text()).toBe('自动');
  });

  it('modelValue 为单选时显示对应技能名称标签', () => {
    const wrapper = mount(SkillSelector, {
      props: { modelValue: ['weekly-report-expert'], skills: createSkills() },
    });
    expect(wrapper.find('.skill-tag-auto').exists()).toBe(false);
    const tags = wrapper.findAll('.skill-tag');
    expect(tags).toHaveLength(1);
    expect(tags[0].text()).toBe('周报撰写专家');
  });

  it('点击技能选项触发 update:modelValue（选中/取消）', async () => {
    const wrapper = mount(SkillSelector, {
      props: { modelValue: [], skills: createSkills() },
    });
    // 展开下拉
    await wrapper.find('.skill-trigger').trigger('click');
    const options = wrapper.findAll('.skill-option');
    expect(options).toHaveLength(2);
    // 点击第一个技能 → 选中
    await options[0].trigger('click');
    expect(wrapper.emitted('update:modelValue')![0]).toEqual([['weekly-report-expert']]);
  });

  it('disabled 时点击不响应', async () => {
    const wrapper = mount(SkillSelector, {
      props: { modelValue: [], skills: createSkills(), disabled: true },
    });
    await wrapper.find('.skill-trigger').trigger('click');
    expect(wrapper.find('.skill-dropdown').exists()).toBe(false);
  });

  it('空技能列表时显示提示', async () => {
    const wrapper = mount(SkillSelector, {
      props: { modelValue: [], skills: [] },
    });
    await wrapper.find('.skill-trigger').trigger('click');
    expect(wrapper.find('.skill-empty').text()).toContain('暂无可用技能');
  });
});
