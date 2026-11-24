// Run against tools/qa/serve_fixture.py. The two modes bracket the Android test.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');
const frontend = path.resolve(process.env.POLKA_FRONTEND || '.verification/Polka/frontend');
const { chromium } = createRequire(path.join(frontend, 'package.json'))('playwright');

async function getJson(context, url) {
  const response = await context.request.get(url);
  assert.equal(response.status(), 200, new URL(url).pathname);
  return response.json();
}

async function visiblePassage(page, chapter, text) {
  await page.waitForFunction(({ chapter, text }) => {
    const host = document.querySelector('.epub-container');
    if (!host) return false;
    const clip = host.getBoundingClientRect();
    return [...host.querySelectorAll('iframe')].some(frame => {
      const box = frame.getBoundingClientRect();
      const doc = frame.contentDocument;
      if (!doc || doc.querySelector('h1')?.textContent !== chapter) return false;
      const p = [...doc.querySelectorAll('p')].find(p => p.textContent === text);
      if (!p) return false;
      const rect = p.getBoundingClientRect();
      return rect.width > 0 && rect.height > 0 &&
        rect.left + box.left < clip.right && rect.right + box.left > clip.left &&
        rect.top + box.top < clip.bottom && rect.bottom + box.top > clip.top;
    });
  }, { chapter, text }, { timeout: 15000 });
}

async function waitSaved(context, url, chapter) {
  const deadline = Date.now() + 15000;
  while (Date.now() < deadline) {
    const point = await getJson(context, url);
    if (point.chapter === chapter && point.locator?.startsWith('epubcfi(')) return point;
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  throw Error('Полка не сохранила выбранную главу');
}

async function main() {
  const mode = process.argv[2];
  assert(['seed', 'verify'].includes(mode), 'Выберите seed или verify');
  const password = process.env.POLKA_QA_PASSWORD;
  assert(password, 'Задайте POLKA_QA_PASSWORD для тестового читателя');
  const port = Number(process.env.POLKA_QA_PORT || 18763);
  assert(Number.isInteger(port) && port > 0 && port < 65536);
  const base = 'http://127.0.0.1:' + port;
  const executablePath = [process.env.BROWSER_PATH, 'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'].find(file => file && fs.existsSync(file));
  const browser = await chromium.launch({ executablePath, headless: true });
  try {
    const context = await browser.newContext({ locale: 'ru-RU', viewport: { width: 1000, height: 900 } });
    const page = await context.newPage();
    await page.goto(base);
    await page.getByLabel('Имя пользователя').fill('readerone');
    await page.getByLabel('Пароль', { exact: true }).fill(password);
    const login = page.waitForResponse(r => r.url().endsWith('/auth/login') && r.request().method() === 'POST');
    await page.getByRole('button', { name: 'Войти' }).click();
    assert.equal((await login).status(), 200, 'Вход в тестовую Полку');
    const books = await getJson(context, base + '/api/v1/books?q=' + encodeURIComponent('Северная полка'));
    const work = books.items.find(book => book.title === 'Северная полка');
    const file = work?.editions.flatMap(e => e.files).find(f => f.format === 'epub');
    assert(file, 'Отсутствует тестовый EPUB');
    const progressUrl = base + '/api/v1/reading/progress/' + file.id;
    const before = await getJson(context, progressUrl);
    if (mode === 'verify') {
      assert.match(before.device || '', /Android/i, 'Ожидается позиция, сохранённая Android');
      assert.equal(before.chapter, 'Вторая глава');
      assert.match(before.locator, /^epubcfi\(/);
    }
    await page.goto(base + '/read/' + file.id);
    const contents = page.getByLabel('Содержание', { exact: true });
    await contents.waitFor();
    if (mode === 'seed') {
      await contents.selectOption({ label: 'Вторая глава' });
      await visiblePassage(page, 'Вторая глава', 'Проверка восстановления позиции.');
      await waitSaved(context, progressUrl, 'Вторая глава');
      await contents.selectOption({ label: 'Первая глава' });
      await visiblePassage(page, 'Первая глава', 'Пробная книга для проверки читалки.');
      const point = await waitSaved(context, progressUrl, 'Первая глава');
      console.log('Браузер сохранил', { fileId: file.id, chapter: point.chapter, locator: point.locator, version: point.version });
    } else {
      // No reader navigation in verify. The initial server CFI must bring this paragraph into view.
      await visiblePassage(page, 'Вторая глава', 'Проверка восстановления позиции.');
      console.log('Браузер открыл «Проверка восстановления позиции.»', {
        fileId: file.id, device: before.device, locator: before.locator, version: before.version
      });
    }
  } finally {
    await browser.close();
  }
}

main().catch(e => { console.error(e.message); process.exitCode = 1; });
