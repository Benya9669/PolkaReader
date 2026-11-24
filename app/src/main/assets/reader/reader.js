'use strict';
(() => {
  const pane = document.getElementById('page');
  const stage = document.getElementById('book-turn'), under = document.getElementById('preview');
  const back = document.getElementById('back'), shadow = document.getElementById('shadow');
  let port, book, view, anchor, lastPlace, settings, preview, turn;
  let preparing = Promise.resolve(), spread = false;
  let ready = false, navigating = false;
  let commands = Promise.resolve();
  let chapters = [];
  let marked = new Set();
  const customFonts = new WeakMap();
  const frame = () => new Promise(resolve => requestAnimationFrame(resolve));
  const send = (type, data = {}) => port.postMessage(JSON.stringify({type, ...data}));
  function sizeViewport() {
    // Android WebView can resolve both 100% and 100vh to zero in an embedded
    // layout. Its actual visible viewport is available in CSS pixels here.
    if (window.innerHeight > 0) document.documentElement.style.height = window.innerHeight + 'px';
    if (turn && (pane.clientWidth !== turn.width || pane.clientHeight !== turn.height)) resetTurn(turn);
  }
  window.addEventListener('resize', sizeViewport);
  window.visualViewport?.addEventListener('resize', sizeViewport);
  window.addEventListener('pagehide',() => { ready = false; if (turn) resetTurn(turn); });
  function enqueue(work) {
    commands = commands.then(async () => {
      navigating = true;
      try { await work(); } finally { navigating = false; }
    }).catch(() => send('error', {message: 'Не удалось открыть книгу или перейти к выбранному месту.'}));
  }

  function chapterAt(start) {
    const contents = view.getContents().find(item => item.sectionIndex === start.index);
    let label = null, nearest;
    for (const entry of chapters) {
      if (entry.index > start.index) continue;
      if (entry.index < start.index || !entry.fragment) {
        if (!nearest) label = entry.label;
        continue;
      }
      const target = contents?.document.getElementById(entry.fragment);
      if (!target) continue;
      const cfi = contents.cfiFromNode(target);
      if (view.epubcfi.compare(cfi, start.cfi) <= 0 && (!nearest || view.epubcfi.compare(cfi, nearest) >= 0)) {
        nearest = cfi;
        label = entry.label;
      }
    }
    return label;
  }

  async function readLocation() {
    await frame();
    await frame();
    const loc = await view.currentLocation();
    if (!loc?.start?.cfi) throw new Error('Empty EPUB location');
    const {start} = loc;
    const section = start.index + (start.displayed.page - 1) / Math.max(1, start.displayed.total);
    const percent = loc.atEnd ? 1 : start.percentage ?? section / Math.max(1, book.spine.length);
    return {locator: start.cfi, percentage: Math.max(0, Math.min(1, percent)), chapter: chapterAt(start)};
  }

  function publish(place, persist) {
    anchor = place.locator;
    lastPlace = place;
    send('place', {...place, persist});
  }

  const advance = (rendition, direction) => direction > 0 ? rendition.next() : rendition.prev();
  const clamp = (value, lo, hi) => Math.min(hi, Math.max(lo, value));
  const rtl = () => view.settings.direction === 'rtl';
  const renditionOptions = () => ({width:pane.clientWidth, height:pane.clientHeight,
    spread:spread ? 'always' : 'none', minSpreadWidth:1, flow:'paginated', allowScriptedContent:false, allowPopups:false});

  function resetTurn(t) {
    if (turn !== t) return;
    turn = null;
    t.cancel();
    pane.style.transform = pane.style.clipPath = pane.style.visibility = '';
    under.style.transform = under.style.visibility = '';
    back.style.display = shadow.style.display = '';
    delete stage.dataset.effect;
    delete stage.dataset.progress;
  }

  // Clip a rectangle against the perpendicular bisector of the original and
  // pulled corner. The turned portion reflects across this crease onto the back.
  function clipPolygon(points, distance) {
    const result = [];
    let a = points[points.length - 1], da = distance(a);
    for (const b of points) {
      const db = distance(b);
      if ((da < 0) !== (db < 0)) {
        const f = da / (da - db);
        result.push([a[0] + f * (b[0] - a[0]), a[1] + f * (b[1] - a[1])]);
      }
      if (db >= 0) result.push(b);
      a = b; da = db;
    }
    return result;
  }
  const polygon = points => 'polygon(' + points.map(([x,y]) => x.toFixed(2) + 'px ' + y.toFixed(2) + 'px').join(',') + ')';

  function paintTurn(t) {
    if (turn !== t || !t.loaded || t.effect === 'none') return;
    const p = t.progress, w = t.width, h = t.height, side = t.side;
    stage.dataset.effect = t.effect;
    stage.dataset.progress = p.toFixed(3);
    under.style.visibility = 'visible';
    if (t.effect === 'slide') {
      pane.style.transform = 'translate3d(' + (-side * p * w) + 'px,0,0)';
      under.style.transform = 'translate3d(' + (side * (1 - p) * w) + 'px,0,0)';
      return;
    }
    if (p <= .001 || p >= .999) {
      pane.style.clipPath = p < .5 ? '' : 'inset(0 100% 0 0)';
      back.style.display = shadow.style.display = '';
      return;
    }
    const cx = side > 0 ? w : 0, cy = t.top ? 0 : h;
    const dx = -side * 2 * w * p;
    const pull = Math.min(w * .33, h * .35) * (t.top ? 1 : -1) + clamp(t.dy, -h * .24, h * .24);
    const dy = pull * Math.sin(Math.PI * p), length = Math.hypot(dx,dy);
    const nx = dx / length, ny = dy / length, mx = cx + dx/2, my = cy + dy/2;
    const distance = ([x,y]) => (x-mx)*nx + (y-my)*ny;
    const rect = [[0,0],[w,0],[w,h],[0,h]];
    const flat = clipPolygon(rect,distance);
    const folded = clipPolygon(rect, point => -distance(point)).map(point => {
      const d = distance(point);
      return [point[0] - 2*d*nx, point[1] - 2*d*ny];
    });
    pane.style.clipPath = polygon(flat);
    back.style.display = shadow.style.display = 'block';
    back.style.clipPath = polygon(folded);
    // Project the gradient into page coordinates so the highlight travels
    // alongside the crease instead of remaining fixed on the viewport.
    const angle = Math.atan2(nx,-ny) * 180 / Math.PI;
    const offset = (Math.abs(nx)*w + Math.abs(ny)*h)/2 + (mx-w/2)*nx + (my-h/2)*ny;
    const shade = Math.min(55, length * .14);
    back.style.background = 'linear-gradient(' + angle + 'deg,var(--fold-dark) ' + (offset-1) +
      'px,var(--fold-light) ' + (offset+shade*.38) + 'px,var(--fold) ' + (offset+shade) + 'px)';
    shadow.style.clipPath = polygon(folded.map(([x,y]) => [x+nx*shade*.28,y+ny*shade*.28]));
    shadow.style.background = 'rgba(0,0,0,' + (.16*Math.sin(Math.PI*p)) + ')';
  }

  async function prepareAdjacent(t) {
    if (turn !== t) return;
    if (!preview) {
      // One lazy second rendition shares the opened archive and all blob URLs.
      preview = new ePub.Rendition(book, renditionOptions());
      preview.hooks.content.register(contents => {
        secureContent(contents);
        contents.window.frameElement?.setAttribute('tabindex','-1');
      });
      await preview.attachTo(under);
      for (const cfi of marked) addHighlight(preview,cfi);
    }
    if (turn !== t) return;
    styleText(preview,settings);
    preview.spread(spread ? 'always' : 'none',1);
    preview.resize(t.width,t.height);
    await preview.display(t.anchor);
    if (turn !== t) return;
    await advance(preview,t.direction);
    await frame();
    if (turn === t) { t.loaded = true; paintTurn(t); }
  }

  function beginTurn(direction, y) {
    if (!ready || turn) return null;
    const loc = view.currentLocation();
    if (!loc?.start || (direction > 0 ? loc.atEnd : loc.atStart)) return null;
    const effect = ['slide','curl'].includes(settings.pageTurn) && !matchMedia('(prefers-reduced-motion: reduce)').matches ? settings.pageTurn : 'none';
    const t = {direction, effect, anchor, start:loc.start.cfi, width:pane.clientWidth, height:pane.clientHeight,
      side:direction*(rtl() ? -1 : 1), top:y < pane.clientHeight / 2, dy:0, progress:0, loaded:false};
    t.cancelled = new Promise(resolve => { t.cancel = resolve; });
    turn = t;
    if (effect !== 'none') {
      // Serialize only the preview's layout. A cancelled, slow chapter render
      // cannot paint over a newer drag or overwrite the canonical locator.
      t.prepared = preparing = preparing.then(() => prepareAdjacent(t)).catch(() => { t.effect = 'none'; });
    }
    return t;
  }

  async function settle(t, commit) {
    if (turn !== t || t.settling) return;
    t.settling = true;
    try {
      if (commit && t.prepared) await Promise.race([t.prepared,t.cancelled]);
      if (turn !== t) return;
      if (t.loaded && t.effect !== 'none') {
        const from = t.progress, to = commit ? 1 : 0;
        const duration = 100 + 155 * Math.abs(to - from), start = performance.now();
        let elapsed;
        do {
          await frame();
          if (turn !== t) return;
          elapsed = Math.min(1,(performance.now()-start)/duration);
          t.progress = from + (to-from)*(1-Math.pow(1-elapsed,3));
          paintTurn(t);
        } while (elapsed < 1);
      }
      if (commit) {
        // epub.js measures CFIs using element rectangles. Restore untransformed
        // coordinates before measuring, with the real neighbour covering the swap.
        if (t.loaded) pane.style.visibility = 'hidden';
        pane.style.transform = pane.style.clipPath = '';
        await advance(view,t.direction);
        if (turn !== t || !ready) return;
        const place = await readLocation();
        if (turn === t && ready && place.locator !== t.start) publish(place,true);
      }
    } finally { resetTurn(t); }
  }

  async function move(direction) {
    const t = beginTurn(direction,pane.clientHeight * .8);
    if (t) await settle(t,true);
  }

  function secureContent(contents) {
    const doc = contents.document;
    const meta = doc.createElement('meta');
    meta.httpEquiv = 'Content-Security-Policy';
    meta.content = "default-src 'none'; img-src blob: data:; style-src 'unsafe-inline' blob:; font-src blob: data:; media-src blob: data:; form-action 'none'";
    doc.head.prepend(meta);
  }

  const noteRole = element =>
    ['footnote','endnote','rearnote'].some(role => (element?.getAttribute('epub:type') || '').split(/\s+/).includes(role)) ||
    ['doc-footnote','doc-endnote'].includes(element?.getAttribute('role'));

  async function followReference(contents, link) {
    const href = link.getAttribute('href')?.trim();
    if (!href || /^(?:[a-z][a-z\d+.-]*:|\/\/|\\)/i.test(href)) return;
    const base = new URL(book.spine.get(contents.sectionIndex).href,'https://book.invalid/');
    const url = new URL(href,base);
    if (url.origin !== base.origin) return;
    const section = book.spine.get(url.pathname.slice(1));
    if (!section) return;
    let fragment = url.hash.slice(1);
    try { fragment = decodeURIComponent(fragment); } catch { /* Keep literal fragment. */ }
    const target = section.href + url.hash;
    if (fragment) {
      // Read the packaged spine item without navigating the live rendition.
      const doc = section.index === contents.sectionIndex ? contents.document : await book.load(section.url);
      const element = doc.getElementById(fragment) || doc.getElementsByName?.(fragment)?.[0];
      let note = element;
      while (note && !noteRole(note)) note = note.parentElement;
      if (!note && element && ((link.getAttribute('epub:type') || '').split(/\s+/).includes('noteref') ||
          link.getAttribute('role') === 'doc-noteref')) {
        note = element.closest('aside,li,p,section,div') || element;
      }
      if (note) {
        const copy = note.cloneNode(true);
        for (const el of copy.querySelectorAll('script,style,template,iframe,object,[hidden],a[role="doc-backlink"]')) el.remove();
        for (const el of copy.querySelectorAll('a')) {
          if ((el.getAttribute('epub:type') || '').split(/\s+/).includes('backlink')) el.remove();
        }
        for (const el of copy.querySelectorAll('p,li,br,div')) el.append(doc.createTextNode(' '));
        const text = (copy.textContent || '').replace(/\s+/g,' ').trim().slice(0,8000);
        if (text) { send('footnote',{text,target}); return; }
      }
    }
    await jump({locator:target,link:true});
  }

  function edgeTap(x) {
    const bounds = stage.getBoundingClientRect();
    const fraction = (x-bounds.left)/bounds.width;
    if (fraction < .23 || fraction > .77) {
      const direction = (fraction < .23 ? -1 : 1) * (rtl() ? -1 : 1);
      enqueue(() => move(direction));
    } else send('tap');
  }
  document.body.addEventListener('click', e => { if (ready && e.target === document.body) edgeTap(e.clientX); });

  function prepareContent(contents) {
    secureContent(contents);
    const doc = contents.document, selected = () => contents.window.getSelection()?.isCollapsed === false;
    doc.documentElement.style.touchAction = 'pan-y pinch-zoom';
    let touch, ignoreClickUntil = 0;
    function cancel() {
      if (touch?.session) {
        const t = touch.session;
        enqueue(() => settle(t,false));
      }
      if (touch) ignoreClickUntil = performance.now() + 450;
      touch = null;
    }
    doc.addEventListener('touchstart', e => {
      if (e.touches.length !== 1) { cancel(); return; }
      // A new real tap is distinct from the synthetic click after the previous
      // swipe, even if the user immediately taps to turn back.
      ignoreClickUntil = 0;
      if (turn || navigating || selected() || e.target.closest?.('a,button,input,textarea,select,audio,video,[contenteditable="true"]')) return;
      const p = e.touches[0];
      touch = {id:p.identifier, x:p.screenX, y:p.screenY, pageY:p.clientY + contents.window.frameElement.getBoundingClientRect().top,
        time:performance.now(), lastTime:performance.now(), lastX:p.screenX, velocity:0};
    }, {passive: true});
    doc.addEventListener('touchmove', e => {
      if (!touch) return;
      if (e.touches.length !== 1 || selected()) { cancel(); return; }
      const p = e.touches[0];
      if (p.identifier !== touch.id) { cancel(); return; }
      const dx = p.screenX-touch.x, dy = p.screenY-touch.y, now = performance.now();
      if (!touch.session) {
        if (Math.max(Math.abs(dx),Math.abs(dy)) < 9) return;
        if (Math.abs(dx) <= Math.abs(dy)*1.3 || now-touch.time > 400) { cancel(); return; }
        const direction = (dx < 0 ? 1 : -1)*(rtl() ? -1 : 1);
        touch.session = beginTurn(direction,touch.pageY);
        if (!touch.session) { cancel(); return; }
      }
      if (e.cancelable) e.preventDefault();
      const t = touch.session;
      t.progress = clamp(-dx*t.side/t.width,0,1);
      t.dy = dy;
      touch.velocity = -(p.screenX-touch.lastX)*t.side / Math.max(1,now-touch.lastTime);
      touch.lastTime = now;
      touch.lastX = p.screenX;
      paintTurn(t);
    }, {passive:false});
    doc.addEventListener('touchcancel',cancel,{passive:true});
    doc.addEventListener('touchend', e => {
      if (!touch) return;
      const t = touch.session;
      if (t) {
        const fast = performance.now()-touch.lastTime < 100 && t.progress*t.width > 32 && touch.velocity > .55;
        const commit = !selected() && (t.progress >= .25 || fast);
        if (e.cancelable) e.preventDefault();
        ignoreClickUntil = performance.now()+450;
        enqueue(() => settle(t,commit));
      }
      touch = null;
    }, {passive:false});
    doc.addEventListener('click', e => {
      if (performance.now() < ignoreClickUntil || selected() || turn) {
        e.preventDefault(); e.stopImmediatePropagation(); return;
      }
      const link = e.target.closest?.('a[href]');
      if (link) {
        e.preventDefault();
        e.stopImmediatePropagation();
        enqueue(() => followReference(contents,link));
      } else if (!e.target.closest?.('button,input,textarea,select,audio,video,[contenteditable="true"]')) {
        edgeTap(contents.window.frameElement.getBoundingClientRect().left + e.clientX);
      }
    }, true);
  }

  function styleText(rendition,s) {
    rendition.themes.fontSize(s.fontPercent + '%');
    const custom = s.fontFamily === 'serif' || s.fontFamily === 'sans-serif';
    if (custom) rendition.themes.override('font-family',s.fontFamily,true);
    else if (customFonts.get(rendition)) {
      rendition.themes.removeOverride('font-family');
      // epub.js removes the inline property as well. A publisher can set its
      // body font inline, so restore it from the original packaged spine item.
      for (const contents of rendition.getContents()) {
        const original = book.spine.get(contents.sectionIndex)?.document?.querySelector('body')?.style;
        const family = original?.getPropertyValue('font-family');
        if (family) contents.document.body.style.setProperty('font-family',family,original.getPropertyPriority('font-family'));
      }
    }
    customFonts.set(rendition,custom);
    rendition.themes.override('line-height',String(s.lineHeight),true);
    rendition.themes.override('color',s.theme === 'dark' ? '#ede9df' : '#262620',true);
    rendition.themes.override('background-color',document.body.style.backgroundColor,true);
  }
  function applyStyle(s) {
    settings = s;
    const dark = s.theme === 'dark', sepia = s.theme === 'sepia';
    const background = dark ? '#202321' : sepia ? '#f3e9d3' : '#ffffff';
    document.body.style.backgroundColor = background;
    stage.style.setProperty('--paper',background);
    stage.style.setProperty('--fold-dark',dark ? '#171916' : sepia ? '#b5a588' : '#b5b3ad');
    stage.style.setProperty('--fold-light',dark ? '#3c403a' : sepia ? '#fff5de' : '#fffefa');
    stage.style.setProperty('--fold',dark ? '#2a2d29' : sepia ? '#e3d4b6' : '#e7e5dd');
    document.documentElement.style.setProperty('--margin', s.marginDp + 'px');
    styleText(view,s);
  }

  async function reflow(style, double = spread) {
    if (!ready) return;
    if (turn) resetTurn(turn);
    const saved = anchor;
    if (spread !== double) {
      spread = double;
      stage.dataset.spread = String(spread);
      view.spread(spread ? 'always' : 'none',1);
    }
    if (style) applyStyle(style);
    view.resize(pane.clientWidth, pane.clientHeight, saved);
    await frame();
    if (saved) await view.display(saved);
    // A different line width moves the page boundary. Keep the semantic anchor
    // until the reader actually navigates, even if reflow emits 'relocated'.
  }

  async function jump(action) {
    if (!ready) return;
    if (turn) resetTurn(turn);
    const origin = lastPlace;
    let target = action.locator;
    if (!target && typeof action.percentage === 'number') {
      if (!book.locations.length()) await book.locations.generate(1000);
      target = book.locations.cfiFromPercentage(Math.min(1, Math.max(0, action.percentage)));
    }
    if (!target) throw new Error('Missing EPUB target');
    if (target.startsWith('epubcfi(')) {
      // A selected annotation spans text. Reading progress uses its start point.
      const cfi = new ePub.CFI(target);
      if (cfi.range) { cfi.collapse(true); target = cfi.toString(); }
    }
    await view.display(target);
    const place = await readLocation();
    if (target.startsWith('epubcfi(')) place.locator = target;
    if (action.link && origin) send('linkOrigin',origin);
    publish(place, action.persist !== false);
  }

  function addHighlight(rendition,locator) {
    rendition.annotations.highlight(locator,{},undefined,'polka-highlight',{fill:'#e8b94d','fill-opacity':'.44'});
  }
  function highlights(locators) {
    const next = new Set(locators);
    for (const rendition of [view,preview].filter(Boolean)) {
      for (const locator of marked) if (!next.has(locator)) rendition.annotations.remove(locator,'highlight');
      for (const locator of next) if (!marked.has(locator)) addHighlight(rendition,locator);
    }
    marked = next;
  }

  function collectChapters(items, depth = 0) {
    for (const item of items) {
      const section = book.spine.get(item.href);
      if (section && item.label) {
        let fragment = item.href.split('#')[1] || '';
        try { fragment = decodeURIComponent(fragment); } catch { /* Literal EPUB fragment. */ }
        chapters.push({href: item.href, label: item.label.trim().slice(0,500), index: section.index, fragment, depth});
      }
      if (item.subitems?.length) collectChapters(item.subitems, depth + 1);
    }
  }

  async function open(config) {
    const response = await fetch('publication.epub', {credentials: 'omit'});
    if (!response.ok) throw new Error('EPUB unavailable');
    book = ePub(await response.arrayBuffer(), {replacements: 'blobUrl'});
    while (window.innerHeight <= 0) await frame();
    sizeViewport();
    spread = Boolean(config.spread);
    stage.dataset.spread = String(spread);
    view = book.renderTo(pane,renditionOptions());
    view.hooks.content.register(prepareContent);
    view.on('displayError', () => send('error', {message: 'Не удалось отобразить раздел EPUB.'}));
    view.on('selected', (locator, contents) => {
      const quote = contents.window.getSelection()?.toString().trim().slice(0,4000);
      if (quote && locator.length <= 4000) send('selection', {locator, quote});
    });
    applyStyle(config.settings);
    const nav = await book.loaded.navigation;
    collectChapters(nav.toc);
    send('chapters', {items: chapters});
    await view.display(config.initial?.locator || undefined);
    const rendered = await readLocation();
    ready = true;
    publish(config.initial?.locator ? config.initial : rendered, false);
    highlights(config.highlights || []);
    let width = pane.clientWidth, height = pane.clientHeight;
    new ResizeObserver(() => {
      if (pane.clientWidth === width && pane.clientHeight === height) return;
      width = pane.clientWidth;
      height = pane.clientHeight;
      if (turn) resetTurn(turn);
      if (width > 0 && height > 0) enqueue(() => reflow());
    }).observe(pane);
  }

  // The transferred port belongs to the top-level document. Book frames get
  // neither a JavaScript-to-native interface nor access to server credentials.
  window.addEventListener('message', event => {
    if (port || event.data !== 'polka-reader' || !event.ports[0]) return;
    port = event.ports[0];
    port.onmessage = message => {
      const action = JSON.parse(message.data);
      if (turn && (action.type === 'style' || action.type === 'jump' || action.type === 'link')) resetTurn(turn);
      enqueue(async () => {
        switch (action.type) {
          case 'open': return open(action);
          case 'next': return move(1);
          case 'previous': return move(-1);
          case 'jump': return jump(action);
          case 'link': return jump({locator:action.locator,link:true});
          case 'style': return reflow(action.settings,Boolean(action.spread));
          case 'highlights': return highlights(action.locators);
        }
      });
    };
    send('connected');
  });
})();
