// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import SkillManagementPage from '@/components/SkillManagementPage.vue';
import type { SkillInfo } from '@/types';

/**
 * SkillManagementPage 组件测试（agent-skill Task-21）
 * 验证标准来源：Task-21 验证标准
 * 关联 AC：AC-N06（管理闭环）、AC-H03（校验失败反馈）、AC-S01（创建校验）
 */

vi.mock('@/api/skill', () => ({
  listSkills: vi.fn(),
  createSkill: vi.fn(),
  updateSkill: vi.fn(),
  setSkillEnabled: vi.fn(),
  deleteSkill: vi.fn(),
}));

vi.mock('@/api/tools', () => ({
  fetchAvailableTools: vi.fn(),
  updateToolPermission: vi.fn(),
}));

import * as skillApi from '@/api/skill';
import * as toolsApi from '@/api/tools';

const presetSkill: SkillInfo = {
  id: 'weekly-report-expert',
  name: '周报撰写专家',
  description: '编写专业周报',
  instruction: '按结构输出',
  resources: [],
  scripts: [],
  enabled: true,
  source: 'PRESET',
};

const customSkill: SkillInfo = {
  id: 'custom-1',
  name: '自定义技能',
  description: '自定义描述',
  instruction: '自定义指令',
  resources: [],
  scripts: [
    { name: 'http-get', language: 'shell', description: 'HTTP 获取', params: [], content: 'curl -s "$SKILL_PARAM_URL"' },
  ],
  enabled: true,
  source: 'CUSTOM',
};

describe('SkillManagementPage', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    vi.mocked(toolsApi.fetchAvailableTools).mockResolvedValue({
      tools: [{ id: 'builtin:httpGet', category: 'builtin', name: 'httpGet', description: '', isDefault: false, permission: 'allow' }],
      defaults: [],
    });
  });

  it('加载后展示技能列表（预置在前）', async () => {
    vi.mocked(skillApi.listSkills).mockResolvedValue([customSkill, presetSkill]);
    const wrapper = mount(SkillManagementPage);
    await flushPromises();
    const names = wrapper.findAll('.skill-item-name').map((n) => n.text());
    expect(names).toEqual(['周报撰写专家', '自定义技能']);
  });

  it('新建表单打开并提交创建', async () => {
    vi.mocked(skillApi.listSkills).mockResolvedValue([]);
    vi.mocked(skillApi.createSkill).mockResolvedValue({ ...customSkill, id: 'new-1' });
    const wrapper = mount(SkillManagementPage);
    await flushPromises();

    await wrapper.find('.btn-create').trigger('click');
    expect(wrapper.find('.skill-form').exists()).toBe(true);
    await wrapper.find('.skill-form-title').exists();

    // 填写表单
    await wrapper.find('input[placeholder="如 weekly-report-expert"]').setValue('new-1');
    await wrapper.find('input[placeholder="如 周报撰写专家"]').setValue('新技能');
    await wrapper.find('textarea[placeholder="描述技能适用场景，Agent 据此判断是否激活"]').setValue('描述');
    await wrapper.find('.btn-save').trigger('click');
    await flushPromises();

    expect(skillApi.createSkill).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'new-1', name: '新技能' }),
    );
  });

  it('创建时 id 为空提示错误', async () => {
    vi.mocked(skillApi.listSkills).mockResolvedValue([]);
    const wrapper = mount(SkillManagementPage);
    await flushPromises();
    await wrapper.find('.btn-create').trigger('click');
    await wrapper.find('.btn-save').trigger('click');
    expect(wrapper.find('.form-error').text()).toContain('技能 id 不能为空');
  });

  it('校验失败时展示后端错误消息（AC-H03）', async () => {
    vi.mocked(skillApi.listSkills).mockResolvedValue([]);
    vi.mocked(skillApi.createSkill).mockRejectedValue(new Error('技能内容不合规: 内容包含恶意指令'));
    const wrapper = mount(SkillManagementPage);
    await flushPromises();
    await wrapper.find('.btn-create').trigger('click');
    await wrapper.find('input[placeholder="如 weekly-report-expert"]').setValue('evil');
    await wrapper.find('input[placeholder="如 周报撰写专家"]').setValue('恶意');
    await wrapper.find('textarea[placeholder="描述技能适用场景，Agent 据此判断是否激活"]').setValue('描述');
    await wrapper.find('.btn-save').trigger('click');
    await flushPromises();
    expect(wrapper.find('.form-error').text()).toContain('技能内容不合规');
  });

  it('编辑技能预填表单并提交更新', async () => {
    vi.mocked(skillApi.listSkills).mockResolvedValue([customSkill]);
    vi.mocked(skillApi.updateSkill).mockResolvedValue(customSkill);
    const wrapper = mount(SkillManagementPage);
    await flushPromises();
    await wrapper.findAll('.btn-edit')[0].trigger('click');
    await wrapper.find('.btn-save').trigger('click');
    await flushPromises();
    expect(skillApi.updateSkill).toHaveBeenCalledWith(
      'custom-1',
      expect.objectContaining({ name: '自定义技能' }),
    );
  });

  it('新建表单点击取消可关闭（CR-001 bug 修复）', async () => {
    vi.mocked(skillApi.listSkills).mockResolvedValue([]);
    const wrapper = mount(SkillManagementPage);
    await flushPromises();
    await wrapper.find('.btn-create').trigger('click');
    expect(wrapper.find('.skill-form').exists()).toBe(true);
    await wrapper.find('.btn-cancel').trigger('click');
    expect(wrapper.find('.skill-form').exists()).toBe(false);
  });

  it('脚本表单添加脚本并随创建提交（CR-001）', async () => {
    vi.mocked(skillApi.listSkills).mockResolvedValue([]);
    vi.mocked(skillApi.createSkill).mockResolvedValue({ ...customSkill, id: 's1' });
    const wrapper = mount(SkillManagementPage);
    await flushPromises();
    await wrapper.find('.btn-create').trigger('click');
    await wrapper.find('input[placeholder="如 weekly-report-expert"]').setValue('s1');
    await wrapper.find('input[placeholder="如 周报撰写专家"]').setValue('脚本技能');
    await wrapper.find('.btn-add-script').trigger('click');
    await wrapper.findAll('.script-editor input')[0].setValue('http-get');
    await wrapper.findAll('.script-editor textarea')[0].setValue('curl -s "$SKILL_PARAM_URL"');
    await wrapper.find('.btn-save').trigger('click');
    await flushPromises();
    expect(skillApi.createSkill).toHaveBeenCalledWith(expect.objectContaining({
      scripts: [expect.objectContaining({ name: 'http-get', language: 'shell' })],
    }));
  });

  it('启停开关调用 toggle 接口', async () => {
    vi.mocked(skillApi.listSkills).mockResolvedValue([presetSkill]);
    vi.mocked(skillApi.setSkillEnabled).mockResolvedValue();
    const wrapper = mount(SkillManagementPage);
    await flushPromises();
    await wrapper.find('.btn-toggle').trigger('click');
    await flushPromises();
    expect(skillApi.setSkillEnabled).toHaveBeenCalledWith('weekly-report-expert', false);
  });

  it('删除技能调用删除接口（确认后）', async () => {
    vi.mocked(skillApi.listSkills).mockResolvedValue([customSkill]);
    vi.mocked(skillApi.deleteSkill).mockResolvedValue();
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
    const wrapper = mount(SkillManagementPage);
    await flushPromises();
    await wrapper.find('.btn-delete').trigger('click');
    await flushPromises();
    expect(skillApi.deleteSkill).toHaveBeenCalledWith('custom-1');
    confirmSpy.mockRestore();
  });
});
