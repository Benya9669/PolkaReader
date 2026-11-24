'use strict';
(() => {
  const pane = document.getElementById('page');
  let port, book, view, anchor;
  let ready = false;
  let commands = Promise.resolve();
  let chapters = [];
  let marked = new Set();
  const frame = () => new Promise(resolve => requestAnimationFrame(resolve));
  const send = (type, data = {}) => port.postMessage(JSON.stringify({type, ...data}));
  function enqueue(work) {
    commands = commands.then(work).catch(() => send('error', {message: 'Не удалось открыть книгу или перейти к выбранному месту.'}));
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
    send('place', {...place, persist});
  }

  async function move(direction) {
    if (!ready) return;
    if (direction > 0) await view.next();
    else await view.prev();
    publish(await readLocation(), true);
  }

  function prepareContent(contents) {
    const doc = contents.document;
    const meta = doc.createElement('meta');
    meta.httpEquiv = 'Content-Security-Policy';
    meta.content = "default-src 'none'; img-src blob: data:; style-src 'unsafe-inline' blob:; font-src blob: data:; media-src blob: data:; form-action 'none'";
    doc.head.prepend(meta);
    let start, swiped = false;
    doc.addEventListener('touchstart', e => {
      swiped = false;
      start = e.touches.length === 1 ? e.touches[0] : null;
    }, {passive: true});
    doc.addEventListener('touchend', e => {
      if (!start || !contents.window.getSelection()?.isCollapsed) return;
      const x = e.changedTouches[0].clientX - start.clientX;
      const y = e.changedTouches[0].clientY - start.clientY;
      if (Math.abs(x) > 48 && Math.abs(x) > Math.abs(y) * 1.5) {
        swiped = true;
        enqueue(() => move(x < 0 ? 1 : -1));
      }
      start = null;
    }, {passive: true});
    doc.addEventListener('click', e => {
      if (swiped || !contents.window.getSelection()?.isCollapsed) return;
      const link = e.target.closest?.('a[href]');
      if (link) {
        e.preventDefault();
        e.stopImmediatePropagation();
        const href = link.getAttribute('href');
        const section = book.spine.get(contents.sectionIndex);
        if (href && !/^(?:[a-z][a-z\d+.-]*:|\/\/)/i.test(href)) {
          const url = new URL(href, new URL(section.href, 'https://book.invalid/'));
          const target = url.pathname.slice(1) + url.hash;
          enqueue(() => jump({locator: target, persist: true}));
        }
      } else {
        send('tap');
      }
    }, true);
  }

  function applyStyle(s) {
    const dark = s.theme === 'dark', sepia = s.theme === 'sepia';
    const background = dark ? '#202321' : sepia ? '#f3e9d3' : '#ffffff';
    document.body.style.backgroundColor = background;
    document.documentElement.style.setProperty('--margin', s.marginDp + 'px');
    view.themes.fontSize(s.fontPercent + '%');
    view.themes.override('line-height', String(s.lineHeight), true);
    view.themes.override('color', dark ? '#ede9df' : '#262620', true);
    view.themes.override('background-color', background, true);
  }

  async function reflow(style) {
    if (!ready) return;
    const saved = anchor;
    if (style) applyStyle(style);
    view.resize(pane.clientWidth, pane.clientHeight, saved);
    await frame();
    if (saved) await view.display(saved);
    // A different line width moves the page boundary. Keep the semantic anchor
    // until the reader actually navigates, even if reflow emits 'relocated'.
  }

  async function jump(action) {
    if (!ready) return;
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
    publish(place, action.persist !== false);
  }

  function highlights(locators) {
    const next = new Set(locators);
    for (const locator of marked) {
      if (!next.has(locator)) view.annotations.remove(locator, 'highlight');
    }
    for (const locator of next) {
      if (!marked.has(locator)) view.annotations.highlight(locator, {}, undefined, 'polka-highlight',
        {fill: '#e8b94d', 'fill-opacity': '.44'});
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
    view = book.renderTo(pane, {width: pane.clientWidth, height: pane.clientHeight, spread: 'none',
      flow: 'paginated', allowScriptedContent: false, allowPopups: false});
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
      enqueue(async () => {
        switch (action.type) {
          case 'open': return open(action);
          case 'next': return move(1);
          case 'previous': return move(-1);
          case 'jump': return jump(action);
          case 'style': return reflow(action.settings);
          case 'highlights': return highlights(action.locators);
        }
      });
    };
    send('connected');
  });
})();
