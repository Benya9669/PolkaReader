// Usage: node tools/reader/cfi-probe.cjs <Polka v0.3.0 frontend directory with npm ci>
// Optional BROWSER_PATH selects a local Chromium executable.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const {createRequire} = require('node:module');
const assets = path.resolve(__dirname, '../../app/src/main/assets/reader');
const frontend = path.resolve(process.argv[2] || '.verification/Polka/frontend');
const fromWeb = createRequire(path.join(frontend, 'package.json'));
const {chromium} = fromWeb('playwright');
const JSZip = fromWeb('jszip');
const esc = str => str.replaceAll('&', '&amp;').replaceAll('<', '&lt;');
async function sampleBook() {
  const zip = new JSZip();
  zip.file('mimetype', 'application/epub+zip');
  zip.file('META-INF/container.xml', '<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>');
  zip.file('book.opf', '<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">polka-test</dc:identifier><dc:title>Проверка CFI</dc:title><dc:language>ru</dc:language></metadata><manifest><item id="toc" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/><item id="text" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="text"/></spine></package>');
  zip.file('nav.xhtml', '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Оглавление</title></head><body><nav epub:type="toc"><ol><li><a href="chapter.xhtml">Глава</a></li></ol></nav></body></html>');
  const paragraphs = Array.from({length: 90}, (_, i) => '<p id="p' + i + '">Абзац ' + i + '. ' +
    esc('Тихий ветер шевелил страницы раскрытой книги. '.repeat(5)) + '</p>').join('');
  zip.file('chapter.xhtml', '<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Глава</title></head><body>' + paragraphs + '</body></html>');
  return zip.generateAsync({type: 'nodebuffer'});
}

async function main() {
  const book = await sampleBook();
  const files = new Map([
    ['/web.html', ['text/html', Buffer.from('<!doctype html><meta charset="utf-8"><script src="/web-zip.js"></script><script src="/web-epub.js"></script><div id="page" style="width:620px;height:440px"></div>')]],
    ['/web-zip.js', ['text/javascript', fs.readFileSync(fromWeb.resolve('jszip/dist/jszip.min.js'))]],
    ['/web-epub.js', ['text/javascript', fs.readFileSync(fromWeb.resolve('epubjs/dist/epub.min.js'))]],
    ['/publication.epub', ['application/epub+zip', book]],
  ]);
  for (const name of ['index.html', 'reader.js', 'epub.min.js', 'jszip.min.js']) {
    files.set('/' + name, [name.endsWith('html') ? 'text/html' : 'text/javascript', fs.readFileSync(path.join(assets, name))]);
  }
  const server = http.createServer((req, res) => {
    const entry = files.get(req.url);
    if (!entry) return res.writeHead(404).end();
    res.writeHead(200, {'Content-Type': entry[0] + (entry[0].startsWith('text') ? '; charset=utf-8' : '')}).end(entry[1]);
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let browser;
  try {
    const executablePath = [process.env.BROWSER_PATH, 'C:/Program Files/Google/Chrome/Application/chrome.exe',
      'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'].find(p => p && fs.existsSync(p));
    browser = await chromium.launch({executablePath, headless: true});
    const origin = 'http://127.0.0.1:' + server.address().port;
    const web = await browser.newPage();
    await web.goto(origin + '/web.html');
    // Same opening, rendering and reporting calls as frontend/src/reader.tsx at v0.3.0.
    const start = await web.evaluate(async () => {
      window.book = ePub(await (await fetch('/publication.epub')).arrayBuffer(), {replacements:'blobUrl'});
      window.view = book.renderTo(document.querySelector('#page'), {width:'100%',height:'100%',spread:'none',flow:'paginated',allowScriptedContent:false});
      await view.display();
      await view.next();
      await view.next();
      const loc = await view.currentLocation();
      const range = view.getRange(loc.start.cfi);
      return {cfi:loc.start.cfi, paragraph:range.startContainer.parentElement.closest('p').id};
    });
    const mobile = await browser.newPage({viewport:{width:360,height:520}});
    await mobile.goto(origin + '/index.html');
    await mobile.evaluate(() => {
      const pair = new MessageChannel();
      window.events = [];
      window.port = pair.port1;
      port.onmessage = e => events.push(JSON.parse(e.data));
      window.postMessage('polka-reader', location.origin, [pair.port2]);
    });
    await mobile.waitForFunction(() => events.some(e => e.type === 'connected'));
    const send = msg => mobile.evaluate(m => port.postMessage(JSON.stringify(m)), msg);
    const settings = {fontPercent:110, lineHeight:1.5, marginDp:16, theme:'light'};
    await send({type:'open',initial:{locator:start.cfi,percentage:0.1,chapter:'Глава'},settings});
    const waitPlace = count => mobile.waitForFunction(n => events.filter(e => e.type === 'place').length >= n, count, {timeout:15000});
    await waitPlace(1);
    async function visibleAnchor(page, cfi) {
      return page.evaluate(target => {
        const frame = document.querySelector('iframe'), doc = frame.contentDocument;
        const range = new ePub.CFI(target).toRange(doc);
        const paragraph = range.startContainer.parentElement.closest('p');
        const rect = range.getBoundingClientRect(), outer = frame.getBoundingClientRect();
        const x = outer.left + rect.left, y = outer.top + rect.top;
        return {id:paragraph.id, visible:x >= -2 && x <= innerWidth && y >= -2 && y <= innerHeight};
      }, cfi);
    }
    assert.deepEqual(await visibleAnchor(mobile,start.cfi), {id:start.paragraph,visible:true});
    assert.equal(await mobile.evaluate(() => events.find(e => e.type === 'place').persist), false);
    await send({type:'style',settings:{...settings,fontPercent:145}});
    await mobile.waitForFunction(() => document.querySelector('iframe')?.contentDocument.body.style.fontSize === '145%');
    await mobile.setViewportSize({width:490,height:390});
    await mobile.waitForFunction(() => document.querySelector('.epub-container')?.clientWidth === document.getElementById('page').clientWidth);
    await mobile.waitForFunction(target => {
      const frame = document.querySelector('iframe');
      if (!frame?.contentDocument?.querySelector('p')) return false;
      const rect = new ePub.CFI(target).toRange(frame.contentDocument).getBoundingClientRect();
      const outer = frame.getBoundingClientRect();
      return outer.left + rect.left >= -2 && outer.left + rect.left < innerWidth;
    }, start.cfi);
    assert.deepEqual(await visibleAnchor(mobile,start.cfi), {id:start.paragraph,visible:true});
    assert.equal(await mobile.evaluate(() => events.filter(e => e.type === 'place' && e.persist).length),0);
    await send({type:'next'});
    await waitPlace(2);
    await send({type:'next'});
    await waitPlace(3);
    const end = await mobile.evaluate(() => events.filter(e => e.type === 'place').at(-1));
    const newParagraph = await visibleAnchor(mobile,end.locator);
    assert.notEqual(newParagraph.id,start.paragraph);
    assert.equal(end.persist,true);
    await web.evaluate(cfi => view.display(cfi),end.locator);
    assert.deepEqual(await visibleAnchor(web,end.locator), {id:newParagraph.id,visible:true});
    console.log('PASS web → Android reader HTML → font/width change → next → web');
    console.log(start.paragraph,start.cfi,'→',newParagraph.id,end.locator);
  } finally {
    await browser?.close();
    await new Promise(resolve => server.close(resolve));
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
