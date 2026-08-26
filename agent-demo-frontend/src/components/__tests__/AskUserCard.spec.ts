import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import AskUserCard from '../AskUserCard.vue';
import type { AskUserData } from '@/types';

/**
 * AskUserCard 统一交互卡片测试（unified-chat-mode Task-16）
 * <p>
 * 验证标准来源：unified-chat-mode 任务规划 Task-16 验证标准
 * 关联 AC：AC-T02（text 输入框）、AC-T03（垂直选项）、AC-T04（其他兜底）、AC-H02（取消选项交互）
 * 业务含义：验证统一交互卡片——问题区（图标+问题+追问轮次）、confirm 型垂直整行选项 +
 * "其他"兜底、text 型内嵌输入框、回答锁定态、本地防重复、reply 统一事件出口。
 * </p>
 */

function makeAskUserData(overrides: Partial<AskUserData> = {}): AskUserData {
  return {
    type: 'confirm',
    question: '确认删除文件 test.txt？',
    options: ['确认删除', '取消'],
    retryCount: 0,
    ...overrides,
  };
}

describe('AskUserCard', () => {
  it('问题区渲染：类型图标 + 问题文本', () => {
    const wrapper = mount(AskUserCard, { props: { askUserData: makeAskUserData() } });
    expect(wrapper.find('.type-icon').text()).toBe('✅');
    expect(wrapper.find('.question-text').text()).toBe('确认删除文件 test.txt？');
  });

  it('retryCount > 0 时显示"第 N 次追问"提示', () => {
    const wrapper = mount(AskUserCard, {
      props: { askUserData: makeAskUserData({ retryCount: 1 }) },
    });
    expect(wrapper.find('.retry-hint').text()).toContain('第 2 次追问');
  });

  it('confirm 型：垂直整行选项（每项含序号徽标 + 文字），点击发出 reply(选项值)', async () => {
    const wrapper = mount(AskUserCard, { props: { askUserData: makeAskUserData() } });
    const rows = wrapper.findAll('.option-row');
    expect(rows.length).toBe(2);
    // 序号徽标
    expect(rows[0].find('.option-index').text()).toBe('1');
    expect(rows[0].find('.option-label').text()).toBe('确认删除');

    await rows[0].trigger('click');
    expect(wrapper.emitted('reply')![0]).toEqual(['确认删除']);
  });

  it('confirm 型：选中后本地锁定（其余项禁用半透明）', async () => {
    const wrapper = mount(AskUserCard, { props: { askUserData: makeAskUserData() } });
    await wrapper.findAll('.option-row')[0].trigger('click');
    // 选中项高亮
    expect(wrapper.findAll('.option-row')[0].classes()).toContain('selected');
    // 未选中项禁用
    expect(wrapper.findAll('.option-row')[1].classes()).toContain('disabled');
    // 二次点击不重复触发
    await wrapper.findAll('.option-row')[1].trigger('click');
    expect(wrapper.emitted('reply')!.length).toBe(1);
  });

  it('confirm 型："其他"入口点击展开输入框，Enter/提交发出 reply(输入文本)（AC-T04）', async () => {
    const wrapper = mount(AskUserCard, { props: { askUserData: makeAskUserData() } });
    await wrapper.find('.other-toggle').trigger('click');
    expect(wrapper.find('.other-input-row').exists()).toBe(true);

    const input = wrapper.find('.other-input-row input');
    await input.setValue('自定义回答');
    await wrapper.find('.other-input-row .submit-btn').trigger('click');
    expect(wrapper.emitted('reply')![0]).toEqual(['自定义回答']);
  });

  it('text 型：内嵌输入框 + 提交按钮，Enter 提交，空值禁用提交（AC-T02）', async () => {
    const wrapper = mount(AskUserCard, {
      props: { askUserData: makeAskUserData({ type: 'text', options: undefined }) },
    });
    const submitBtn = wrapper.find('.text-area .submit-btn');
    // 空值禁用提交
    expect(submitBtn.attributes('disabled')).toBeDefined();

    const input = wrapper.find('.text-area input');
    await input.setValue('ORD-12345');
    expect(wrapper.find('.text-area .submit-btn').attributes('disabled')).toBeUndefined();
    await input.trigger('keyup.enter');
    expect(wrapper.emitted('reply')![0]).toEqual(['ORD-12345']);
  });

  it('锁定态：answer 存在时交互禁用（confirm 选中行保持高亮）', async () => {
    const wrapper = mount(AskUserCard, {
      props: { askUserData: makeAskUserData({ answer: '确认删除' }) },
    });
    // 选中行保持高亮
    expect(wrapper.findAll('.option-row')[0].classes()).toContain('selected');
    // 交互禁用：点击不触发 reply
    await wrapper.findAll('.option-row')[1].trigger('click');
    expect(wrapper.emitted('reply')).toBeUndefined();
  });

  it('锁定态：confirm answer 不在选项中时显示"已回答：{answer}"行', () => {
    const wrapper = mount(AskUserCard, {
      props: { askUserData: makeAskUserData({ answer: '自定义' }) },
    });
    expect(wrapper.find('.answered-line').text()).toContain('已回答：自定义');
  });

  it('锁定态：text 型显示只读"已回答：{answer}"', () => {
    const wrapper = mount(AskUserCard, {
      props: { askUserData: makeAskUserData({ type: 'text', options: undefined, answer: 'ORD-12345' }) },
    });
    expect(wrapper.find('.answered-line').text()).toBe('已回答：ORD-12345');
    expect(wrapper.find('.text-area input').exists()).toBe(false);
  });

  it('disabled prop 生效（外部禁用场景）', async () => {
    const wrapper = mount(AskUserCard, {
      props: { askUserData: makeAskUserData(), disabled: true },
    });
    await wrapper.findAll('.option-row')[0].trigger('click');
    expect(wrapper.emitted('reply')).toBeUndefined();
  });
});
