/**
 * 前端三条链路的浏览器级验证：列表 / 上传（含分片并发与进度）/ 删除。
 *
 * 光靠 curl 测后端接口证明不了页面能用——MD5 是页面自己实现的、并发池是页面
 * 自己控的，这些只有真的在浏览器里跑一遍才算数。
 */
// playwright 不在本项目依赖里（不想为了一个验证脚本引入 node_modules）。
// 用 PLAYWRIGHT_PATH 指向任意一份已安装的副本，缺省是这台机器上现成的那个。
const PW = process.env.PLAYWRIGHT_PATH || 'D:/Codex/geo-monitor/node_modules/playwright';
const { chromium } = require(PW);

const BASE = (process.env.FS_BASE || 'http://127.0.0.1:8080').replace(/\/$/, '');
const FILE = process.env.UI_TEST_FILE || 'D:/Codex/ui-test-tmp/ui_12m.bin';
const OUT = process.env.UI_TEST_OUT || 'D:/Codex/ui-test-tmp';

const pass = [];
const fail = [];
function check(cond, desc, extra) {
  if (cond) { pass.push(desc); console.log('  \u2705 ' + desc); }
  else { fail.push(desc); console.log('  \u274c ' + desc + (extra ? '  <- ' + JSON.stringify(extra) : '')); }
}

(async () => {
  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await ctx.newPage();

  const consoleErrors = [];
  page.on('console', m => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', e => consoleErrors.push('pageerror: ' + e.message));
  page.on('dialog', d => d.accept());

  console.log('\n[1] 页面加载');
  await page.goto(BASE + '/', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1000);

  check((await page.title()) === '文件存储服务', '页面标题正确');
  check(await page.isVisible('#drop'), '拖拽上传区已渲染');

  const rows0 = await page.locator('#files tr').count();
  check(rows0 > 0, `文件列表渲染出 ${rows0} 行`);

  // MD5 自检失败会 toast 一条错误，并留下 console.error
  check(!consoleErrors.some(e => /自检失败/.test(e)), 'MD5 标准向量自检通过');

  await page.screenshot({ path: OUT + '/ui-1-loaded.png', fullPage: true });

  console.log('\n[2] 上传 12MB 文件');
  await page.setInputFiles('#picker', FILE);

  // 进度条要真的动起来：先抓一个中间态，再等终态
  let sawProgress = false;
  for (let i = 0; i < 40; i++) {
    const t = await page.textContent('.job-state').catch(() => '');
    if (/上传中/.test(t)) { sawProgress = true; break; }
    if (/计算指纹/.test(t)) { sawProgress = true; break; }
    await page.waitForTimeout(100);
  }
  check(sawProgress, '上传过程中出现过进度文案（不是一步到位）');

  await page.waitForFunction(() => {
    const s = document.querySelector('.job-state');
    return s && /完成|失败|秒传/.test(s.textContent);
  }, { timeout: 90000 });

  const stateText = (await page.textContent('.job-state')).trim();
  check(/完成|秒传/.test(stateText), `任务终态正常：${stateText}`);
  check(!/失败/.test(stateText), '没有出现失败状态', stateText);

  const barWidth = await page.getAttribute('.job .bar > i', 'style');
  check(/width:\s*100%/.test(barWidth || ''), '进度条走到 100%', barWidth);

  await page.waitForTimeout(1500);
  const rows1 = await page.locator('#files tr').count();
  check(rows1 === rows0 + 1, `列表从 ${rows0} 行增加到 ${rows1} 行`);

  const hasFile = await page.locator('#files td.name', { hasText: 'ui_12m.bin' }).count();
  check(hasFile === 1, '新文件出现在列表里');

  await page.screenshot({ path: OUT + '/ui-2-uploaded.png', fullPage: true });

  console.log('\n[3] 重复上传同一文件应命中秒传');
  await page.setInputFiles('#picker', FILE);
  await page.waitForFunction(() => {
    const els = document.querySelectorAll('.job-state');
    const last = els[els.length - 1];
    return last && /完成|失败|秒传/.test(last.textContent);
  }, { timeout: 60000 });
  const states = await page.locator('.job-state').allTextContents();
  const lastState = states[states.length - 1].trim();
  check(/秒传/.test(lastState), `第二次上传命中秒传：${lastState}`, lastState);

  await page.waitForTimeout(1200);
  const rows2 = await page.locator('#files tr').count();
  check(rows2 === rows1 + 1, `秒传也新增了一条引用（${rows1} -> ${rows2}）`);

  await page.screenshot({ path: OUT + '/ui-3-instant.png', fullPage: true });

  console.log('\n[4] 删除刚上传的两条引用');
  for (let i = 0; i < 2; i++) {
    const btn = page.locator('#files tr', { hasText: 'ui_12m.bin' }).first().locator('button', { hasText: '删除' });
    await btn.click();
    await page.waitForTimeout(1200);
  }
  const rows3 = await page.locator('#files tr').count();
  check(rows3 === rows0, `两条引用删除后回到 ${rows0} 行，实际 ${rows3}`);
  const gone = await page.locator('#files td.name', { hasText: 'ui_12m.bin' }).count();
  check(gone === 0, '列表里不再有该文件');

  await page.screenshot({ path: OUT + '/ui-4-deleted.png', fullPage: true });

  console.log('\n[5] 控制台');
  const realErrors = consoleErrors.filter(e => !/favicon/i.test(e));
  check(realErrors.length === 0, '控制台没有错误', realErrors.slice(0, 3));

  await browser.close();

  console.log('\n' + '='.repeat(46));
  console.log(`通过 ${pass.length} 条，失败 ${fail.length} 条`);
  process.exit(fail.length === 0 ? 0 : 1);
})().catch(e => { console.error('脚本异常:', e); process.exit(1); });
