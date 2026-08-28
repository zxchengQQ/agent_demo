import { defineStore } from 'pinia';
import * as skillApi from '@/api/skill';
import type { SkillInfo } from '@/types';

/**
 * 技能管理状态
 *
 * 业务含义：作为跨组件共享的技能数据唯一来源，封装 API 调用。
 * 写操作（创建/更新/删除/启停）后自动刷新列表，保证页面数据一致性。
 */

export const useSkillStore = defineStore('skill', {
  state: () => ({
    /** 技能列表 */
    skills: [] as SkillInfo[],
    /** 列表加载中状态 */
    loading: false,
  }),

  actions: {
    /**
     * 加载技能列表
     * 业务含义：从后端拉取全部技能，调用失败时保持空列表不崩溃（错误由页面捕获提示）。
     */
    async loadSkills() {
      this.loading = true;
      try {
        this.skills = await skillApi.listSkills();
      } catch (e) {
        this.skills = [];
        throw e;
      } finally {
        this.loading = false;
      }
    },

    /** 创建技能（写后自动刷新列表） */
    async createSkill(payload: skillApi.SkillRequestPayload) {
      await skillApi.createSkill(payload);
      await this.loadSkills();
    },

    /** 更新技能（写后自动刷新列表） */
    async updateSkill(skillId: string, payload: skillApi.SkillRequestPayload) {
      await skillApi.updateSkill(skillId, payload);
      await this.loadSkills();
    },

    /** 启用/禁用技能（写后自动刷新列表） */
    async toggleEnabled(skillId: string, enabled: boolean) {
      await skillApi.setSkillEnabled(skillId, enabled);
      await this.loadSkills();
    },

    /** 删除技能（写后自动刷新列表） */
    async deleteSkill(skillId: string) {
      await skillApi.deleteSkill(skillId);
      await this.loadSkills();
    },
  },
});
