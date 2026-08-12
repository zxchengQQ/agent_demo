import { describe, it, expect, beforeEach } from 'vitest';
import {
  saveLlmConfig,
  loadLlmConfig,
  clearLlmConfig,
  saveLastUsedModelId,
  loadLastUsedModelId,
} from './llm-storage';
import type { VendorRequest } from '@/types';

/**
 * LLM 配置 localStorage 封装测试
 */

/** 构造测试用 VendorRequest */
function makeVendorRequest(): VendorRequest {
  return {
    name: '火山引擎',
    type: 'predefined',
    baseUrl: 'https://ark.cn-beijing.volces.com/api/v3',
    apiKey: 'sk-test-key',
    thinkingTrigger: 'enabled',
    timeout: 60000,
    maxRetries: 3,
    temperature: 0.7,
    models: [
      { modelName: 'doubao-seed-2.0-pro', displayName: '豆包Seed 2.0 Pro', type: 'chat', supportsVision: true },
    ],
  };
}

describe('LLM 配置 localStorage 封装', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  describe('saveLlmConfig / loadLlmConfig', () => {
    it('无数据时 loadLlmConfig 返回 null', () => {
      expect(loadLlmConfig()).toBeNull();
    });

    it('saveLlmConfig 后 loadLlmConfig 返回保存的配置', () => {
      const vendors = [makeVendorRequest()];
      saveLlmConfig(vendors);

      const result = loadLlmConfig();
      expect(result).toEqual(vendors);
      expect(result).toHaveLength(1);
      expect(result![0].apiKey).toBe('sk-test-key');
    });

    it('saveLlmConfig 保存多个厂商配置', () => {
      const vendor1 = makeVendorRequest();
      const vendor2: VendorRequest = {
        ...vendor1,
        name: 'OpenAI',
        baseUrl: 'https://api.openai.com/v1',
        apiKey: 'sk-openai-key',
      };
      saveLlmConfig([vendor1, vendor2]);

      const result = loadLlmConfig();
      expect(result).toHaveLength(2);
      expect(result![1].name).toBe('OpenAI');
    });

    it('saveLlmConfig 保存空数组', () => {
      saveLlmConfig([]);
      const result = loadLlmConfig();
      expect(result).toEqual([]);
    });
  });

  describe('clearLlmConfig', () => {
    it('清除已保存的配置', () => {
      saveLlmConfig([makeVendorRequest()]);
      expect(loadLlmConfig()).not.toBeNull();

      clearLlmConfig();
      expect(loadLlmConfig()).toBeNull();
    });

    it('无配置时调用不抛异常', () => {
      expect(() => clearLlmConfig()).not.toThrow();
    });
  });

  describe('saveLastUsedModelId / loadLastUsedModelId', () => {
    it('无数据时 loadLastUsedModelId 返回 null', () => {
      expect(loadLastUsedModelId()).toBeNull();
    });

    it('saveLastUsedModelId 后 loadLastUsedModelId 返回保存的值', () => {
      saveLastUsedModelId('doubao-seed-2.0-pro');
      expect(loadLastUsedModelId()).toBe('doubao-seed-2.0-pro');
    });

    it('saveLastUsedModelId 覆盖旧值', () => {
      saveLastUsedModelId('model-a');
      saveLastUsedModelId('model-b');
      expect(loadLastUsedModelId()).toBe('model-b');
    });
  });
});
