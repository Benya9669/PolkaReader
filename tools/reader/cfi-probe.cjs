// Usage: node tools/reader/cfi-probe.cjs <Polka v0.3.0 frontend directory with npm ci>
// Optional BROWSER_PATH selects a local Chromium executable.
// Optional POLKA_READER_PREVIEW_DIR saves real mid-gesture slide and curl PNGs.
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
async function sampleBook(rtl = false) {
  const zip = new JSZip();
  zip.file('mimetype', 'application/epub+zip');
  zip.file('META-INF/container.xml', '<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>');
  zip.file('book.opf', '<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">polka-test</dc:identifier><dc:title>Проверка CFI</dc:title><dc:language>ru</dc:language></metadata><manifest><item id="toc" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/><item id="text" href="chapter.xhtml" media-type="application/xhtml+xml"/><item id="notes" href="notes.xhtml" media-type="application/xhtml+xml"/></manifest><spine page-progression-direction="' + (rtl?'rtl':'ltr') + '"><itemref idref="text"/><itemref idref="notes"/></spine></package>');
  zip.file('nav.xhtml', '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Оглавление</title></head><body><nav epub:type="toc"><ol><li><a href="chapter.xhtml">Глава</a></li></ol></nav></body></html>');
  const paragraphs = Array.from({length: 90}, (_, i) => '<p id="p' + i + '">Абзац ' + i + '. ' +
    esc('Тихий ветер шевелил страницы раскрытой книги. '.repeat(5)) + '</p>').join('');
  zip.file('chapter.xhtml', '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Глава</title></head><body style="font-family:Georgia,serif">' + paragraphs +
    '<p><a id="ref" href="notes.xhtml#f1" epub:type="noteref">Сноска</a> · <a id="seealso" href="notes.xhtml#more"><span id="link-label">См. также</span></a> · <a id="external" href="https://example.invalid/info">Внешняя ссылка</a></p></body></html>');
  zip.file('notes.xhtml','<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Примечания</title></head><body><aside epub:type="footnote" id="f1"><p>Полярная <em>звезда</em> указывает на север. <a role="doc-backlink" href="chapter.xhtml#ref">Назад</a></p></aside><p id="more">Дополнительный материал</p><img id="map" alt="Карта мыса" width="20" height="10" src="data:image/svg+xml,%3Csvg%20xmlns=%22http://www.w3.org/2000/svg%22%20width=%22200%22%20height=%22100%22%3E%3Crect%20width=%22200%22%20height=%22100%22%20fill=%22green%22/%3E%3C/svg%3E"/></body></html>');
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
  for (const name of ['index.html', 'reader.js', 'reader-tools.js', 'epub.min.js', 'jszip.min.js']) {
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
    const mobile = await browser.newPage({viewport:{width:360,height:520},hasTouch:true});
    await mobile.goto(origin + '/index.html');
    await mobile.evaluate(() => {
      // Simulate the zero-height root reported by an Android WebView embedded
      // in a Compose layout, despite window.innerHeight reporting the viewport.
      document.documentElement.style.height = '0px';
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
        const hit = document.elementFromPoint(Math.max(0, x + 1), Math.max(0, y + 1));
        return {id:paragraph.id, visible:x >= -2 && x <= innerWidth && y >= -2 && y <= innerHeight && hit === frame};
      }, cfi);
    }
    assert.deepEqual(await visibleAnchor(mobile,start.cfi), {id:start.paragraph,visible:true});
    assert.equal(await mobile.evaluate(() => document.getElementById('page').clientHeight),520);
    assert.equal(await mobile.evaluate(() => events.find(e => e.type === 'place').persist), false);
    function linkPaint() {
      const doc = document;
      const link = doc.getElementById('ref'), win = doc.defaultView;
      link.focus({preventScroll:true});
      const style = win.getComputedStyle(link);
      const paint = {color:style.color, underline:style.textDecorationLine, outline:style.outlineStyle,
        focus:style.outlineColor, visited:style.getPropertyValue('--polka-visited').trim(),
        label:win.getComputedStyle(doc.getElementById('link-label')).color};
      link.blur();
      return paint;
    }
    for (const [theme,color,focus,visited] of [
      ['light','rgb(7, 91, 71)','rgb(145, 82, 10)','#674478'],
      ['dark','rgb(133, 215, 186)','rgb(246, 198, 111)','#d5b3eb'],
      ['sepia','rgb(49, 91, 66)','rgb(131, 74, 16)','#704c64']
    ]) {
      await send({type:'style',settings:{...settings,theme}});
      await mobile.waitForFunction(t => document.querySelector('#page iframe').contentDocument.documentElement.dataset.polkaPaper === t,theme);
      const frame = await (await mobile.locator('#page iframe').elementHandle()).contentFrame();
      assert.deepEqual(await frame.evaluate(linkPaint),
        {color,underline:'underline',outline:'solid',focus,visited,label:color});
    }
    assert.equal(await mobile.evaluate(() => events.filter(e => e.type === 'place').length),1);
    await send({type:'style',settings:{...settings,fontPercent:145,fontFamily:'serif'}});
    await mobile.waitForFunction(() => {
      const style = document.querySelector('iframe')?.contentDocument.body.style;
      return style?.fontSize === '145%' && style.fontFamily === 'serif';
    });
    await send({type:'style',settings:{...settings,fontPercent:145,fontFamily:'book'}});
    await mobile.waitForFunction(() => document.querySelector('iframe')?.contentDocument.body.style.fontFamily.includes('Georgia'));
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
    const marked = await web.evaluate(() => {
      const contents = view.getContents()[0], text = contents.document.getElementById('p24').firstChild;
      const range = contents.document.createRange();
      range.setStart(text, 6);
      range.setEnd(text, 22);
      return contents.cfiFromRange(range);
    });
    await send({type:'jump',locator:marked});
    await waitPlace(4);
    const point = await mobile.evaluate(() => events.filter(e => e.type === 'place').at(-1).locator);
    assert(!point.includes(','));
    assert.deepEqual(await visibleAnchor(mobile,point), {id:'p24',visible:true});
    await send({type:'jump',percentage:.64});
    await waitPlace(5);
    const alternate = await mobile.evaluate(() => events.filter(e => e.type === 'place').at(-1));
    assert.match(alternate.locator,/^epubcfi\(/);
    assert.equal(alternate.persist,true);
    await web.evaluate(cfi => view.display(cfi),alternate.locator);
    assert((await visibleAnchor(web,alternate.locator)).visible);
    console.log('PASS web → Android reader HTML → font/width change → next → web');
    console.log(start.paragraph,start.cfi,'→',newParagraph.id,end.locator);
    console.log('PASS annotation range → start point; alternate percentage → native CFI → web');
    const places = () => mobile.evaluate(() => events.filter(e => e.type === 'place').length);
    const captureDir = process.env.POLKA_READER_PREVIEW_DIR;
    if (captureDir) fs.mkdirSync(captureDir,{recursive:true});
    const cdp = await mobile.context().newCDPSession(mobile);
    const touch = (type,x,y) => cdp.send('Input.dispatchTouchEvent',{
      type,touchPoints:x === undefined ? [] : [{x,y,id:1}]});
    async function touchMove(x,y,dx,dy) {
      await touch('touchStart',x,y);
      for (let i = 1; i <= 5; ++i) await touch('touchMove',x+dx*i/5,y+dy*i/5);
    }
    const idle = () => mobile.waitForFunction(() => !document.querySelector('#book-turn').dataset.effect);
    const waitFrame = effect => mobile.waitForFunction(name => {
      const stage = document.querySelector('#book-turn');
      return stage.dataset.effect === name && Number(stage.dataset.progress) > .025;
    },effect,{timeout:4000});
    async function styling(effect,double=false,theme='light') {
      await send({type:'style',spread:double,settings:{...settings,pageTurn:effect,theme}});
      // A following non-persistent jump provides a deterministic queue barrier.
      const n = await places();
      await send({type:'jump',locator:start.cfi,persist:false});
      await waitPlace(n+1);
    }
    for (const effect of ['slide','curl']) {
      await styling(effect,false,effect === 'slide' ? 'dark' : 'sepia');
      const n = await places();
      await touchMove(375,270,-165,-12);
      await waitFrame(effect);
      const geometry = await mobile.evaluate(() => {
        const stage = document.querySelector('#book-turn'), under = document.querySelector('#preview');
        return {progress:Number(stage.dataset.progress),inside:under.inert && under.getAttribute('aria-hidden') === 'true',
          adjacent:Boolean(under.querySelector('iframe')?.contentDocument.querySelector('p')),
          linkColor:under.querySelector('iframe').contentWindow.getComputedStyle(under.querySelector('iframe').contentDocument.getElementById('ref')).color,
          under:getComputedStyle(under).transform, paper:getComputedStyle(document.querySelector('#page')).transform,
          crease:getComputedStyle(document.querySelector('#back')).clipPath};
      });
      assert(geometry.progress > .25 && geometry.progress < .6 && geometry.adjacent && geometry.inside);
      assert.equal(geometry.linkColor,effect === 'slide' ? 'rgb(133, 215, 186)' : 'rgb(49, 91, 66)');
      if (effect === 'slide') { assert.match(geometry.under,/matrix/); assert.match(geometry.paper,/matrix/); }
      else assert.match(geometry.crease,/^polygon\(/);
      if (captureDir) await mobile.screenshot({path:path.join(captureDir,'epub-'+effect+'.png')});
      assert.equal(await places(),n);
      await touch('touchEnd');
      await waitPlace(n+1);
      await idle();
      const last = await mobile.evaluate(() => events.filter(e => e.type === 'place').at(-1));
      assert(last.persist && last.locator !== start.cfi);
      await web.evaluate(cfi => view.display(cfi),last.locator);
      assert((await visibleAnchor(web,last.locator)).visible);

      await touchMove(230,250,35,2); await waitFrame(effect);
      await mobile.waitForTimeout(160); // Slow short drag must snap back.
      await touch('touchEnd'); await idle();
      await touchMove(210,155,2,72); await touch('touchEnd');
      await mobile.waitForTimeout(300);
      assert.equal(await places(),n+1);
      await touchMove(200,225,135,0); await waitFrame(effect); await touch('touchCancel'); await idle();
      assert.equal(await places(),n+1);
      await send({type:'previous'}); await waitPlace(n+2);
      console.log('PASS '+effect+' follows a real touch, exposes adjacent page, commits once, cancels short/vertical/interrupted gestures and works by button');
    }
    await styling('none');
    const n = await places();
    await mobile.touchscreen.tap(240,185);
    await mobile.waitForFunction(() => events.some(e => e.type === 'tap'));
    await mobile.touchscreen.tap(435,185);
    await waitPlace(n+1);
    assert.equal(await mobile.locator('#book-turn[data-effect]').count(),0);
    await mobile.touchscreen.tap(52,185); await waitPlace(n+2);
    const beforeNote = await mobile.evaluate(() => events.filter(e => e.type === 'place').at(-1));
    async function clickBookLink(id) {
      await mobile.evaluate(id => document.querySelector('#page iframe').contentDocument.getElementById(id).click(),id);
    }
    await clickBookLink('ref');
    await mobile.waitForFunction(() => events.some(e => e.type === 'footnote'));
    const note = await mobile.evaluate(() => events.find(e => e.type === 'footnote'));
    assert.equal(note.text,'Полярная звезда указывает на север.');
    assert.equal(await places(),n+2);
    await send({type:'link',locator:note.target}); await waitPlace(n+3);
    assert.equal(await mobile.evaluate(() => events.filter(e => e.type === 'linkOrigin').at(-1).locator),beforeNote.locator);
    await send({type:'jump',locator:beforeNote.locator}); await waitPlace(n+4);
    const externalRequests = [];
    mobile.on('request',req => { if (req.url().startsWith('https://example.invalid/')) externalRequests.push(req.url()); });
    await clickBookLink('external');
    await clickBookLink('link-label'); await waitPlace(n+5);
    assert.equal(await mobile.evaluate(() => events.filter(e => e.type === 'linkOrigin').at(-1).locator),beforeNote.locator);
    assert.equal(await mobile.evaluate(() => events.filter(e => e.type === 'linkOrigin').length),2);
    assert.deepEqual(externalRequests,[]);
    assert.equal(await mobile.evaluate(() => document.querySelector('#page iframe').sandbox.contains('allow-scripts')),false);

    const writes = () => mobile.evaluate(() => events.filter(e => e.type === 'place' && e.persist).length);
    const oldWrites = await writes();
    await mobile.setViewportSize({width:1160,height:540});
    await styling('none',true);
    const columnCount = () => mobile.evaluate(() => {
      const doc = document.querySelector('#page iframe').contentDocument;
      const columnWidth = parseFloat(doc.defaultView.getComputedStyle(doc.documentElement).columnWidth) ||
        parseFloat(doc.defaultView.getComputedStyle(doc.body).columnWidth);
      return document.querySelector('#page').clientWidth / columnWidth;
    });
    assert((await columnCount()) > 1.8, 'Landscape must show two actual columns');
    assert((await visibleAnchor(mobile,start.cfi)).visible);

    // Measure both real columns and their text at the top of each spread.
    // A one-column turn would repeat half the previous spread.
    function spreadContent() {
      const stage = document.querySelector('#page'), container = stage.querySelector('.epub-container');
      const iframe = stage.querySelector('iframe'), doc = iframe.contentDocument;
      const outer = iframe.getBoundingClientRect(), bounds = stage.getBoundingClientRect();
      const firstText = [0,.5].map(left => {
        for (const p of doc.querySelectorAll('p[id]')) {
          const range = doc.createRange();
          range.selectNodeContents(p);
          for (const rect of range.getClientRects()) {
            const x = rect.left + outer.left - bounds.left;
            if (x >= bounds.width*left && x < bounds.width*(left+.5) && rect.top >= 0 && rect.top < bounds.height) {
              const point = doc.caretRangeFromPoint(rect.left+1,rect.top+1);
              if (point) return [p.id,point.startOffset];
            }
          }
        }
        return null;
      });
      return {width:bounds.width,offset:container.scrollLeft,firstText};
    }
    const spreadBefore = await mobile.evaluate(spreadContent);
    assert(spreadBefore.firstText.every(Boolean));
    assert.notDeepEqual(...spreadBefore.firstText);
    const nSpread = await places();
    await send({type:'next'}); await waitPlace(nSpread+1);
    const spreadAfter = await mobile.evaluate(spreadContent);
    assert(spreadAfter.firstText.every(Boolean));
    assert.notDeepEqual(spreadBefore.firstText[1],spreadAfter.firstText[0]);
    assert.equal(spreadAfter.offset - spreadBefore.offset,spreadBefore.width);
    await send({type:'previous'}); await waitPlace(nSpread+2);
    assert.deepEqual((await mobile.evaluate(spreadContent)).firstText,spreadBefore.firstText);

    // Delay only the decorative second rendition, as for a slow image-heavy
    // chapter. Rotate after finger release while its neighbour is still pending.
    await styling('curl',true);
    await mobile.evaluate(() => {
      const original = ePub.Rendition.prototype.next;
      ePub.Rendition.prototype.next = async function(...args) {
        if (this.manager.container.closest('#preview') && window.holdPreview) {
          window.previewWaiting = true;
          await new Promise(resolve => { window.resumePreview = resolve; });
        }
        return original.apply(this,args);
      };
      window.holdPreview = true;
    });
    const nRotate = await places();
    await touchMove(800,300,-435,-15);
    await mobile.waitForFunction(() => window.previewWaiting);
    await touch('touchEnd');
    await mobile.setViewportSize({width:600,height:540});
    await send({type:'style',spread:false,settings:{...settings,pageTurn:'curl'}});
    await mobile.waitForFunction(() => document.querySelector('#book-turn').dataset.spread === 'false');
    await mobile.evaluate(() => { window.holdPreview = false; window.resumePreview(); });
    await idle();
    await mobile.waitForTimeout(300);
    assert.equal(await places(),nRotate);
    assert((await visibleAnchor(mobile,start.cfi)).visible);
    // Resize a fully painted fold while the user's finger is still down.
    await touchMove(435,265,-205,-5); await waitFrame('curl');
    await mobile.setViewportSize({width:565,height:440}); await touch('touchEnd');
    await idle(); await mobile.waitForTimeout(240);
    assert.equal(await places(),nRotate);
    assert((await visibleAnchor(mobile,start.cfi)).visible);

    await send({type:'style',spread:false,settings:{...settings,pageTurn:'none'}});
    await mobile.waitForFunction(() => document.querySelector('#book-turn').dataset.spread === 'false');
    await mobile.waitForFunction(() => document.querySelector('#page').clientWidth <= 760);
    await mobile.waitForTimeout(150);
    assert((await columnCount()) < 1.5);
    assert.equal(await writes(),oldWrites+2);
    assert((await visibleAnchor(mobile,start.cfi)).visible);
    assert.deepEqual(await mobile.evaluate(() => events.filter(e => e.type === 'error')),[]);
    console.log('PASS edge and centre taps, plain text cross-chapter footnote, internal navigation return CFI, true spread and single-page reflow');

    const selectCount = await places();
    await mobile.evaluate(locator => {
      const doc = document.querySelector('#page iframe').contentDocument;
      const range = new ePub.CFI(locator).toRange(doc);
      range.selectNodeContents(range.startContainer.parentElement.closest('p'));
      const selection = doc.defaultView.getSelection();
      selection.removeAllRanges(); selection.addRange(range);
    },start.cfi);
    // A selection owns the gesture, including when TalkBack clicks an edge.
    await mobile.evaluate(() => {
      const doc = document.querySelector('#page iframe').contentDocument;
      doc.body.dispatchEvent(new MouseEvent('click',{bubbles:true,clientX:8}));
    });
    await mobile.waitForTimeout(150);
    assert.equal(await places(),selectCount);
    assert(await mobile.evaluate(() => !document.querySelector('#page iframe').contentWindow.getSelection().isCollapsed));

    const closingEvents = [];
    await mobile.exposeFunction('reportClosingPlace',message => closingEvents.push(message));
    await mobile.evaluate(() => {
      document.querySelector('#page iframe').contentWindow.getSelection().removeAllRanges();
      port.addEventListener('message', event => {
        const value = JSON.parse(event.data);
        if (value.type === 'place' && value.persist) window.reportClosingPlace(value);
      });
      window.previewWaiting = false; window.holdPreview = true;
    });
    await styling('curl');
    await touchMove(440,255,-230,0);
    await mobile.waitForFunction(() => window.previewWaiting);
    await touch('touchEnd');
    // The document is disposed while the released turn is still preparing.
    await mobile.goto('about:blank');
    assert.deepEqual(closingEvents,[]);
    console.log('PASS two visible text columns turn together and back, resize during a fold or pending neighbour and closing mid-turn preserve CFI');

    await mobile.setViewportSize({width:490,height:390});
    // The same archive with RTL progression should advance from the left edge.
    const rtlBook = await sampleBook(true);
    await mobile.route('**/publication.epub',route => route.fulfill({contentType:'application/epub+zip',body:rtlBook}));
    await mobile.goto(origin + '/index.html');
    await mobile.evaluate(() => {
      const channel = new MessageChannel();
      window.events = []; window.port = channel.port1;
      port.onmessage = e => events.push(JSON.parse(e.data));
      window.postMessage('polka-reader',location.origin,[channel.port2]);
    });
    await mobile.waitForFunction(() => events.some(e => e.type === 'connected'));
    await send({type:'open',settings:{...settings,pageTurn:'slide'}}); await waitPlace(1);
    await send({type:'previous'});
    await mobile.waitForTimeout(250);
    assert.equal(await places(),1);
    await mobile.touchscreen.tap(52,180); await waitPlace(2);
    await touchMove(125,260,165,4); await waitFrame('slide');
    assert(await mobile.evaluate(() => new DOMMatrix(getComputedStyle(document.querySelector('#page')).transform).m41 > 0));
    await touch('touchEnd'); await waitPlace(3);
    const rtlForward = await mobile.evaluate(() => events.filter(e => e.type === 'place')[2].locator);
    await mobile.touchscreen.tap(435,180); await waitPlace(4);
    const rtlBack = await mobile.evaluate(() => events.filter(e => e.type === 'place')[3].locator);
    assert(await mobile.evaluate(([a,b]) => new ePub.CFI().compare(a,b)>0,[rtlForward,rtlBack]));
    console.log('PASS selection keeps its text; RTL edge taps and swipe advance in reading order, beginning has no phantom writes');
    const beforeInspection = await places();
    const report = async (type,id) => {
      await mobile.waitForFunction(([type,id]) => events.some(e => e.type === type && e.id === id),[type,id]);
      return mobile.evaluate(([type,id]) => events.find(e => e.type === type && e.id === id),[type,id]);
    };
    await send({type:'previewPosition',percentage:.9,id:30});
    const preview = await report('positionPreview',30);
    assert.match(preview.locator,/^epubcfi\(/);
    assert(preview.excerpt.length > 0);
    await send({type:'search',query:'ПОЛЯРНАЯ  звезда',id:31});
    const search = await report('searchResults',31);
    assert.equal(search.items.length,1);
    assert.match(search.items[0].excerpt,/Полярная звезда/);
    await send({type:'search',query:'Тихий',id:32});
    assert.equal((await report('searchResults',32)).limited,true);
    await send({type:'style',settings:{...settings,chapterHint:'pages'}});
    await mobile.waitForFunction(() => events.some(e => e.type === 'chapterRemainder' && e.pages > 0));
    await send({type:'style',settings:{...settings,chapterHint:'minutes'}});
    await mobile.waitForFunction(() => events.some(e => e.type === 'chapterRemainder' && e.minutes > 0));
    assert.equal(await places(),beforeInspection);
    await send({type:'jump',locator:search.items[0].locator}); await waitPlace(beforeInspection+1);
    assert.equal(await mobile.evaluate(() => events.filter(e => e.type === 'place').at(-1).locator),search.items[0].locator);
    await mobile.evaluate(() => document.querySelector('#page iframe').contentDocument.getElementById('map').click());
    await mobile.waitForFunction(() => !document.querySelector('#illustration').hidden && document.querySelector('#illustration img').naturalWidth > 0);
    await mobile.locator('[data-plus]').click();
    assert.match(await mobile.locator('.image-surface img').evaluate(el => el.style.transform),/scale\(1.5\)/);
    await send({type:'closeIllustration'});
    await mobile.waitForFunction(() => document.querySelector('#illustration').hidden);
    assert.equal(await places(),beforeInspection+1);
    await mobile.evaluate(() => {
      const doc = document.querySelector('#page iframe').contentDocument;
      const table = doc.createElement('table');
      table.insertRow().insertCell().textContent = 'Столбец';
      doc.body.append(table); table.rows[0].cells[0].click();
    });
    assert.equal(await mobile.locator('.table-overlay td').innerText(),'Столбец');
    await mobile.getByText('Закрыть таблицу').click();
    assert.equal(await places(),beforeInspection+1);
    await mobile.goto(origin + '/index.html');
    await mobile.evaluate(() => {
      const channel = new MessageChannel();
      window.events = []; window.port = channel.port1;
      port.onmessage = e => events.push(JSON.parse(e.data));
      window.postMessage('polka-reader',location.origin,[channel.port2]);
    });
    await mobile.waitForFunction(() => events.some(e => e.type === 'connected'));
    await send({type:'open',initial:{locator:start.cfi,percentage:.1},settings:{...settings,scrollBook:true,hyphenate:true,alignment:'justify'}});
    await waitPlace(1);
    assert.equal(await mobile.evaluate(()=>events.find(e=>e.type==='place').locator),start.cfi);
    assert.equal(await mobile.frameLocator('#page iframe').first().locator('p').first().evaluate(p=>getComputedStyle(p).textAlign),'justify');
    await send({type:'next'}); await waitPlace(2);
    assert.notEqual(await mobile.evaluate(()=>events.filter(e=>e.type==='place').at(-1).locator),start.cfi);
    const previousScroll = await places();
    await mobile.mouse.move(230,180);
    await mobile.mouse.wheel(0,420);
    await waitPlace(previousScroll+1);
    await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:145,y:143},{x:190,y:143}]});
    await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
    await mobile.waitForFunction(()=>events.some(e=>e.type==='line'),null,{timeout:5000});
    assert.match(await mobile.evaluate(()=>events.find(e=>e.type==='line').locator),/^epubcfi/);
    console.log('PASS full-screen table, typography, continuous EPUB restore and movement');
    console.log('PASS cross-chapter, cross-inline EPUB search, limited results, noncommitting exact preview, chapter hints and image zoom preserve CFI');
  } finally {
    await browser?.close();
    await new Promise(resolve => server.close(resolve));
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
