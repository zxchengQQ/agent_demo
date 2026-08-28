import { describe, it, expect, beforeEach, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

/**
 * 技能管理状态测试
 *
 * 测试策略：mock '../api/skill' 模块，仅验证 store 的 state 流转与写后刷新。
 */

vi.mock('../api/skill', () => ({
  listSkills: vi.fn(),
  createSkill: vi.fn(),
  updateSkill: vi.fn(),
  setSkillEnabled: vi.fn(),
  deleteSkill: vi.fn(),
}));

import { useSkillStore } from './skill';
import {
  listSkills,
  createSkill,
  updateSkill,
  setSkillEnabled,
  deleteSkill,
} from '../api/skill';
import type { SkillInfo } from '@/types';

/** 技能 mock 数据 */
const mockSkill: SkillInfo = {
  id: 'weekly-report-expert',
  name: '周报撰写专家',
  description: '编写专业周报',
  instruction: '按结构输出周报',
  resources: [{ name: 'template', content: '模板' }],
  scripts: [],
  enabled: true,
  source: 'PRESET',
};

describe('skill store', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
  });

  it('loadSkills 拉取列表并填充 state', async () => {
    vi.mocked(listSkills).mockResolvedValue([mockSkill]);
    const store = useSkillStore();
    await store.loadSkills();
    expect(store.skills).toHaveLength(1);
    expect(store.skills[0].id).toBe('weekly-report-expert');
    expect(store.loading).toBe(false);
  });

  it('loadSkills 失败时保持空列表并抛出', async () => {
    vi.mocked(listSkills).mockRejectedValue(new Error('网络异常'));
    const store = useSkillStore();
    await expect(store.loadSkills()).rejects.toThrow('网络异常');
    expect(store.skills).toEqual([]);
  });

  it('createSkill 后自动刷新列表', async () => {
    vi.mocked(createSkill).mockResolvedValue(mockSkill);
    vi.mocked(listSkills).mockResolvedValue([mockSkill]);
    const store = useSkillStore();
    await store.createSkill({
      id: 'new-skill',
      name: '新技能',
      description: '描述',
      instruction: '指令',
    });
    expect(createSkill).toHaveBeenCalled();
    expect(listSkills).toHaveBeenCalled();
    expect(store.skills).toHaveLength(1);
  });

  it('toggleEnabled 调用启停接口并刷新', async () => {
    vi.mocked(setSkillEnabled).mockResolvedValue();
    vi.mocked(listSkills).mockResolvedValue([{ ...mockSkill, enabled: false }]);
    const store = useSkillStore();
    await store.toggleEnabled('weekly-report-expert', false);
    expect(setSkillEnabled).toHaveBeenCalledWith('weekly-report-expert', false);
    expect(store.skills[0].enabled).toBe(false);
  });

  it('deleteSkill 调用删除接口并刷新', async () => {
    vi.mocked(deleteSkill).mockResolvedValue();
    vi.mocked(listSkills).mockResolvedValue([]);
    const store = useSkillStore();
    await store.deleteSkill('weekly-report-expert');
    expect(deleteSkill).toHaveBeenCalledWith('weekly-report-expert');
    expect(store.skills).toEqual([]);
  });
});
