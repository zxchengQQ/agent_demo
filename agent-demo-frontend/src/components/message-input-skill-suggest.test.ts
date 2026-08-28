// @vitest-environment jsdom
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import MessageInput from '@/components/MessageInput.vue';
import type { LlmModel, SkillInfo } from '@/types';

/**
 * MessageInput /skill 技能列表选择测试（CR-001 交互改进）
 * 验证：输入 /skill 前缀时弹出已有技能列表，点击技能补全为 "/skill 技能id " 供继续输入消息。
 */

vi.mock('@/api/skill', () => ({
  listSkills: vi.fn(),
}));

import { listSkills } from '@/api/skill';
import { useSkillStore } from '@/stores/skill';

const chatModel: LlmModel = {
  id: 'model-a',
  vendorId: 'v1',
  vendorName: '火山引擎',
  modelName: 'doubao-seed-2.0-pro',
  displayName: '豆包Seed 2.0 Pro',
  type: 'chat',
  supportsVision: true,
};

const skills: SkillInfo[] = [
  { id: 'weekly-report-expert', name: '周报撰写专家', description: '编写专业周报', instruction: '指令', resources: [], scripts: [], enabled: true, source: 'PRESET' },
  { id: 'data-query-assistant', name: '数据查询助手', description: '查询数据', instruction: '指令', resources: [], scripts: [], enabled: true, source: 'PRESET' },
  { id: 'tech-doc-writer', name: '技术文档撰写助手', description: '写文档', instruction: '指令', resources: [], scripts: [], enabled: true, source: 'PRESET' },
];

describe('MessageInput /skill 技能列表选择', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    vi.mocked(listSkills).mockResolvedValue(skills);
    const skillStore = useSkillStore();
    skillStore.skills = skills;
  });

  async function mountInput() {
    const wrapper = mount(MessageInput, { props: { isStreaming: false, models: [chatModel] } });
    await flushPromises();
    return wrapper;
  }

  it('输入 /skill 前缀时弹出技能列表', async () => {
    const wrapper = await mountInput();
    expect(wrapper.find('.skill-suggest').exists()).toBe(false);
    await wrapper.find('textarea').setValue('/skill');
    expect(wrapper.find('.skill-suggest').exists()).toBe(true);
    const items = wrapper.findAll('.skill-suggest-item');
    expect(items.length).toBe(3);
    expect(wrapper.find('.skill-suggest').text()).toContain('数据查询助手');
  });

  it('输入非 /skill 前缀时不显示技能列表', async () => {
    const wrapper = await mountInput();
    await wrapper.find('textarea').setValue('帮我查数据');
    expect(wrapper.find('.skill-suggest').exists()).toBe(false);
    // 仅 /plan 前缀也不显示技能列表
    await wrapper.find('textarea').setValue('/plan 做计划');
    expect(wrapper.find('.skill-suggest').exists()).toBe(false);
  });

  it('点击技能补全为 /skill 技能id 供继续输入', async () => {
    const wrapper = await mountInput();
    await wrapper.find('textarea').setValue('/skill');
    // 选择项用 @mousedown.prevent（保持输入框聚焦），测试触发 mousedown
    await wrapper.findAll('.skill-suggest-item')[1].trigger('mousedown');
    const textarea = wrapper.find('textarea');
    expect((textarea.element as HTMLTextAreaElement).value).toBe('/skill data-query-assistant ');
  });

  it('输入 /skill 部分技能名时过滤列表', async () => {
    const wrapper = await mountInput();
    await wrapper.find('textarea').setValue('/skill 数据');
    const items = wrapper.findAll('.skill-suggest-item');
    expect(items.length).toBe(1);
    expect(items[0].text()).toContain('数据查询助手');
  });

  it('流式中不显示技能列表', async () => {
    const wrapper = mount(MessageInput, { props: { isStreaming: true, models: [chatModel] } });
    await wrapper.find('textarea').setValue('/skill');
    expect(wrapper.find('.skill-suggest').exists()).toBe(false);
  });
});
