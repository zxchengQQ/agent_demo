import { describe, it, expect, vi, beforeEach } from 'vitest';
import * as skillApi from './skill';

/**
 * 技能管理 API 封装测试
 *
 * 测试策略：mock 全局 fetch，验证 URL/方法/载荷与 Result<T> 解析。
 */

const mockFetch = vi.fn();

beforeEach(() => {
  global.fetch = mockFetch;
  vi.clearAllMocks();
});

/** 构造 Result<T> 响应 */
function jsonResponse<T>(data: T, success = true, message = '成功') {
  return {
    ok: true,
    json: async () => ({ success, code: success ? 200 : 400, message, data, traceId: '' }),
  } as unknown as Response;
}

describe('skill api', () => {
  it('listSkills 请求 /api/skill/list 并返回 data', async () => {
    mockFetch.mockResolvedValue(jsonResponse([{ id: 's1' }]));
    const result = await skillApi.listSkills();
    expect(mockFetch).toHaveBeenCalledWith('/api/skill/list', undefined);
    expect(result).toEqual([{ id: 's1' }]);
  });

  it('createSkill 发送 POST 与 JSON 载荷', async () => {
    mockFetch.mockResolvedValue(jsonResponse({ id: 's1' }));
    const payload = { id: 's1', name: '技能', description: '描述', instruction: '指令' };
    await skillApi.createSkill(payload);
    expect(mockFetch).toHaveBeenCalledWith(
      '/api/skill',
      expect.objectContaining({
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      }),
    );
  });

  it('updateSkill 请求 PUT /api/skill/{id}', async () => {
    mockFetch.mockResolvedValue(jsonResponse({ id: 's1' }));
    await skillApi.updateSkill('s1', { name: '改名', description: 'd', instruction: 'i' });
    expect(mockFetch).toHaveBeenCalledWith(
      '/api/skill/s1',
      expect.objectContaining({ method: 'PUT' }),
    );
  });

  it('setSkillEnabled 请求 PUT /api/skill/{id}/enabled', async () => {
    mockFetch.mockResolvedValue(jsonResponse(null));
    await skillApi.setSkillEnabled('s1', false);
    expect(mockFetch).toHaveBeenCalledWith(
      '/api/skill/s1/enabled',
      expect.objectContaining({ method: 'PUT', body: JSON.stringify({ enabled: false }) }),
    );
  });

  it('deleteSkill 请求 DELETE /api/skill/{id}', async () => {
    mockFetch.mockResolvedValue(jsonResponse(null));
    await skillApi.deleteSkill('s1');
    expect(mockFetch).toHaveBeenCalledWith(
      '/api/skill/s1',
      expect.objectContaining({ method: 'DELETE' }),
    );
  });

  it('业务失败时抛出后端消息', async () => {
    mockFetch.mockResolvedValue(jsonResponse(null, false, '技能内容不合规'));
    await expect(skillApi.createSkill({
      id: 's1', name: 'n', description: 'd', instruction: 'i',
    })).rejects.toThrow('技能内容不合规');
  });
});
