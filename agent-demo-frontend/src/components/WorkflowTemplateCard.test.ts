import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import WorkflowTemplateCard from './WorkflowTemplateCard.vue';
import type { WorkflowTemplateSummary } from '@/types';

/**
 * WorkflowTemplateCard 组件测试（P2 Task-18，AC-032）
 */

function makeTemplate(overrides: Partial<WorkflowTemplateSummary> = {}): WorkflowTemplateSummary {
  return {
    id: 'tpl-1',
    name: '质量评分-修订',
    description: '循环打磨内容',
    mode: 'LOOP',
    agentCount: 2,
    parameters: [{ name: 'content', type: 'string', required: true, description: '初稿' }],
    ...overrides,
  };
}

describe('WorkflowTemplateCard', () => {
  it('应渲染模板名称、描述、Agent 数量', () => {
    const wrapper = mount(WorkflowTemplateCard, {
      props: { template: makeTemplate() },
    });
    expect(wrapper.text()).toContain('质量评分-修订');
    expect(wrapper.text()).toContain('循环打磨内容');
    expect(wrapper.text()).toContain('2 个 Agent');
  });

  it('LOOP 模式显示"循环"标签', () => {
    const wrapper = mount(WorkflowTemplateCard, {
      props: { template: makeTemplate({ mode: 'LOOP' }) },
    });
    expect(wrapper.text()).toContain('循环');
  });

  it('PARALLEL 模式显示"并行"标签', () => {
    const wrapper = mount(WorkflowTemplateCard, {
      props: { template: makeTemplate({ mode: 'PARALLEL' }) },
    });
    expect(wrapper.text()).toContain('并行');
  });

  it('CONDITIONAL 模式显示"条件"标签', () => {
    const wrapper = mount(WorkflowTemplateCard, {
      props: { template: makeTemplate({ mode: 'CONDITIONAL' }) },
    });
    expect(wrapper.text()).toContain('条件');
  });

  it('点击卡片触发 select 事件', async () => {
    const template = makeTemplate();
    const wrapper = mount(WorkflowTemplateCard, {
      props: { template },
    });
    await wrapper.trigger('click');
    expect(wrapper.emitted('select')).toBeTruthy();
    expect(wrapper.emitted('select')![0][0]).toEqual(template);
  });
});
