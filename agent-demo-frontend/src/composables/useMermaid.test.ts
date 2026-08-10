import { describe, it, expect, beforeEach } from 'vitest';
import { enhanceMermaidBlocks } from './useMermaid';

/**
 * Mermaid 渲染 composable 测试
 * 验证 Mermaid 代码块检测、SVG 渲染、渲染图/源码切换功能
 */
describe('useMermaid - enhanceMermaidBlocks', () => {
  /**
   * 创建包含 Mermaid 代码块的容器
   */
  function createContainer(mermaidSource: string): HTMLElement {
    const container = document.createElement('div');
    container.innerHTML = `<pre><code class="language-mermaid">${mermaidSource}</code></pre>`;
    document.body.appendChild(container);
    return container;
  }

  /**
   * 清理测试 DOM
   */
  function cleanup() {
    document.body.innerHTML = '';
  }

  beforeEach(() => {
    cleanup();
  });

  it('检测 language-mermaid 代码块并替换为 mermaid-block 容器', async () => {
    const container = createContainer('graph TD\n    A-->B');

    await enhanceMermaidBlocks(container);

    const mermaidBlock = container.querySelector('.mermaid-block');
    expect(mermaidBlock).toBeTruthy();
    // 原始 <pre> 应被移除
    expect(container.querySelector('pre > code.language-mermaid')).toBeNull();
  });

  it('mermaid-block 包含渲染图/源码两个切换按钮', async () => {
    const container = createContainer('graph TD\n    A-->B');

    await enhanceMermaidBlocks(container);

    const buttons = container.querySelectorAll('.mermaid-btn');
    expect(buttons.length).toBe(2);
    expect(buttons[0].textContent).toBe('渲染图');
    expect(buttons[1].textContent).toBe('源码');
  });

  it('默认创建渲染图区域和源码区域（两者都存在）', async () => {
    const container = createContainer('graph TD\n    A-->B');

    await enhanceMermaidBlocks(container);

    const diagram = container.querySelector('.mermaid-diagram');
    const source = container.querySelector('.mermaid-source');
    expect(diagram).toBeTruthy();
    expect(source).toBeTruthy();
    // 源码区域应包含原始代码
    expect(source?.querySelector('code')?.textContent).toBe('graph TD\n    A-->B');
  });

  it('点击"源码"按钮切换到源码模式（源码可见，渲染图隐藏）', async () => {
    const container = createContainer('graph TD\n    A-->B');

    await enhanceMermaidBlocks(container);

    const sourceBtn = container.querySelector('.mermaid-btn[data-mode="source"]') as HTMLButtonElement;
    sourceBtn.click();

    const diagram = container.querySelector('.mermaid-diagram') as HTMLElement;
    const source = container.querySelector('.mermaid-source') as HTMLElement;
    expect(diagram.style.display).toBe('none');
    expect(source.style.display).not.toBe('none');
  });

  it('点击"渲染图"按钮切换回渲染模式', async () => {
    const container = createContainer('graph TD\n    A-->B');

    await enhanceMermaidBlocks(container);

    // 先切到源码
    const sourceBtn = container.querySelector('.mermaid-btn[data-mode="source"]') as HTMLButtonElement;
    sourceBtn.click();
    // 再切回渲染图
    const renderBtn = container.querySelector('.mermaid-btn[data-mode="render"]') as HTMLButtonElement;
    renderBtn.click();

    const diagram = container.querySelector('.mermaid-diagram') as HTMLElement;
    const source = container.querySelector('.mermaid-source') as HTMLElement;
    expect(diagram.style.display).not.toBe('none');
    expect(source.style.display).toBe('none');
  });

  it('源码区域包含原始 Mermaid 源码', async () => {
    const source = 'graph TD\n    A-->B';
    const container = createContainer(source);

    await enhanceMermaidBlocks(container);

    const sourceEl = container.querySelector('.mermaid-source code');
    expect(sourceEl?.textContent).toBe(source);
  });

  it('非 Mermaid 代码块不被增强', async () => {
    const container = document.createElement('div');
    container.innerHTML = `<pre><code class="language-javascript">console.log('hello');</code></pre>`;
    document.body.appendChild(container);

    await enhanceMermaidBlocks(container);

    expect(container.querySelector('.mermaid-block')).toBeNull();
    expect(container.querySelector('code.language-javascript')).toBeTruthy();
  });

  it('无效 Mermaid 语法时显示错误信息并自动切换到源码模式', async () => {
    const container = createContainer('this is not valid mermaid syntax @@@');

    await enhanceMermaidBlocks(container);

    const errorEl = container.querySelector('.mermaid-error');
    expect(errorEl).toBeTruthy();
    expect(errorEl?.textContent).toContain('渲染失败');
    // 应自动切换到源码模式
    const source = container.querySelector('.mermaid-source') as HTMLElement;
    expect(source.style.display).not.toBe('none');
  });
});
