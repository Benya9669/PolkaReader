'use strict';

// Inspect detached chapter documents. Neither search nor scrubber previews navigate the rendition.
window.publicationTools = (() => {
  const blockSelector = 'p,li,dt,dd,h1,h2,h3,h4,h5,h6,blockquote,figcaption,td,th,pre,div,section,article';
  const compact = text => text.replace(/\s+/g, ' ').trim();

  function paragraphs(doc) {
    const body = doc.querySelector('body') || doc.documentElement;
    const walker = doc.createTreeWalker(body, NodeFilter.SHOW_TEXT);
    const blocks = [];
    let node, current;
    while ((node = walker.nextNode())) {
      if (!node.length || node.parentElement?.closest('script,style,template,[hidden],head')) continue;
      const parent = node.parentElement?.closest(blockSelector) || body;
      if (!current || current.parent !== parent) {
        current = {parent, nodes:[], parts:[], length:0};
        blocks.push(current);
      }
      current.nodes.push({node, start:current.length});
      current.parts.push(node.data);
      current.length += node.length;
    }
    return blocks.map(block => ({...block,text:block.parts.join('')})).filter(block => /\S/.test(block.text));
  }

  function point(section, block, offset) {
    const part = block.nodes.find(part => offset < part.start + part.node.length) || block.nodes.at(-1);
    const range = part.node.ownerDocument.createRange();
    range.setStart(part.node, Math.min(part.node.length, Math.max(0, offset - part.start)));
    range.collapse(true);
    return section.cfiFromRange(range);
  }

  const snippet = (text, offset, length) => (offset > 52 ? '…' : '') +
    compact(text.slice(Math.max(0, offset - 52), offset + length + 64)) +
    (offset + length + 64 < text.length ? '…' : '');

  function queryPattern(query) {
    return new RegExp(query.trim().split(/\s+/).map(word => word.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('\\s+'), 'gi');
  }

  function explorer(book, chapters, send) {
    let searchId = 0, previewId = 0;
    const label = (section, doc) => chapters.find(entry => entry.index === section.index)?.label ||
      compact(doc.querySelector('h1,h2,title')?.textContent || '') || 'Раздел ' + (section.index + 1);
    async function search(query, id) {
      const token = ++searchId;
      if (!query.trim()) return;
      const items = [];
      try {
        const pattern = queryPattern(query);
        for (const section of book.spine.spineItems) {
          if (token !== searchId) return;
          const doc = await book.load(section.url);
          if (token !== searchId) return;
          const title = label(section, doc);
          for (const block of paragraphs(doc)) {
            pattern.lastIndex = 0;
            let match;
            while ((match = pattern.exec(block.text))) {
              if (items.length === 100) {
                send('searchResults', {id,items,limited:true});
                return;
              }
              items.push({label:title, locator:point(section, block, match.index),
                excerpt:snippet(block.text, match.index, match[0].length)});
            }
          }
          // Release the detached document after this section and yield for cancellation.
          await new Promise(resolve => setTimeout(resolve, 0));
        }
        if (token === searchId) send('searchResults', {id,items});
      } catch {
        if (token === searchId) send('searchResults', {id,items,error:'Не удалось проверить все главы. Попробуйте ещё раз.'});
      }
    }
    async function preview(fraction, id) {
      const token = ++previewId;
      try {
        const sections = book.spine.spineItems;
        const overall = Math.max(0, Math.min(1, fraction)) * sections.length;
        const index = Math.min(sections.length - 1, Math.floor(overall));
        const section = sections[index];
        const doc = await book.load(section.url);
        if (token !== previewId) return;
        const title = label(section, doc);
        const blocks = paragraphs(doc);
        if (!blocks.length) {
          send('positionPreview', {id,label:title,excerpt:'Раздел с иллюстрациями',locator:section.href});
          return;
        }
        let remaining = Math.floor((overall - index) * Math.max(0, blocks.reduce((sum,b) => sum + b.length,0) - 1));
        let block = blocks[0];
        for (const next of blocks) {
          block = next;
          if (remaining < block.length) break;
          remaining -= block.length;
        }
        send('positionPreview', {id,label:title,excerpt:compact(block.text).slice(0,240),
          locator:point(section,block,Math.min(remaining,block.length-1))});
      } catch {
        if (token === previewId) send('positionPreview', {id,error:'Не удалось получить этот фрагмент.'});
      }
    }
    return {search,preview,cancel() { ++searchId; ++previewId; }};
  }

  function chapterRemainder(view, chapters, mode) {
    const loc = view.currentLocation();
    if (!loc?.start || !loc.end || mode === 'off' || !mode) return null;
    const contents = view.getContents().find(item => item.sectionIndex === loc.start.index);
    if (!contents) return null;
    const doc = contents.document;
    const start = new ePub.CFI(loc.start.cfi).toRange(doc);
    if (!start) return null;
    let boundary = null;
    for (const chapter of chapters) {
      if (chapter.index !== loc.start.index || !chapter.fragment) continue;
      const element = doc.getElementById(chapter.fragment);
      if (!element) continue;
      const target = doc.createRange();
      target.selectNodeContents(element);
      target.collapse(true);
      if (start.compareBoundaryPoints(Range.START_TO_START,target) < 0 &&
          (!boundary || target.compareBoundaryPoints(Range.START_TO_START,boundary) < 0)) boundary = target;
    }
    if (mode === 'minutes') {
      const rest = doc.createRange();
      rest.selectNodeContents(doc.body);
      if (boundary) rest.setEnd(boundary.startContainer,boundary.startOffset);
      rest.setStart(start.startContainer,start.startOffset);
      return {minutes:Math.ceil((rest.toString().match(/\S+/g) || []).length / 180)};
    }
    let lastPage = loc.start.displayed.total;
    if (boundary && view.manager.layout.pageWidth > 0) {
      const bounds = boundary.startContainer.nodeType === Node.ELEMENT_NODE ? boundary.startContainer.getBoundingClientRect() :
        boundary.getBoundingClientRect();
      const x = view.settings.direction === 'rtl' ? doc.documentElement.scrollWidth - bounds.right : bounds.left;
      lastPage = Math.min(lastPage, Math.max(1, Math.ceil((x + 1) / view.manager.layout.pageWidth)));
    }
    return {pages:Math.max(0,lastPage - loc.end.displayed.page)};
  }

  // Use the already displayed publication resource. The top document never follows arbitrary book URLs.
  function illustrationViewer(send) {
    const container = document.getElementById('illustration');
    const image = container.querySelector('img'), surface = container.querySelector('.image-surface');
    const pointers = new Map();
    let scale = 1, x = 0, y = 0, ownedUrl, previousFocus;
    function render() {
      const maxX = Math.max(0,(image.clientWidth * scale - surface.clientWidth) / 2);
      const maxY = Math.max(0,(image.clientHeight * scale - surface.clientHeight) / 2);
      x = Math.max(-maxX,Math.min(maxX,x)); y = Math.max(-maxY,Math.min(maxY,y));
      image.style.transform = 'translate(' + x + 'px,' + y + 'px) scale(' + scale + ')';
    }
    function magnify(value, atX = 0, atY = 0) {
      const next = Math.min(8,Math.max(1,value)), ratio = next / scale;
      x = atX + (x - atX) * ratio; y = atY + (y - atY) * ratio;
      scale = next;
      render();
    }
    function close() {
      if (container.hidden) return;
      container.hidden = true;
      document.getElementById('book-turn').removeAttribute('inert');
      pointers.clear(); image.removeAttribute('src');
      if (ownedUrl) URL.revokeObjectURL(ownedUrl);
      ownedUrl = null;
      previousFocus?.focus?.({preventScroll:true});
      send('illustration', {open:false});
    }
    container.querySelector('[data-close]').onclick = close;
    container.querySelector('[data-plus]').onclick = () => magnify(scale * 1.5);
    container.querySelector('[data-minus]').onclick = () => magnify(scale / 1.5);
    container.addEventListener('keydown', e => { if (e.key === 'Escape') close(); });
    const distance = ([a,b]) => Math.hypot(a.x-b.x,a.y-b.y);
    surface.addEventListener('pointerdown', e => {
      surface.setPointerCapture(e.pointerId);
      pointers.set(e.pointerId, {x:e.clientX,y:e.clientY});
    });
    surface.addEventListener('pointermove', e => {
      const previous = pointers.get(e.pointerId);
      if (!previous) return;
      const before = [...pointers.values()].slice(0,2);
      pointers.set(e.pointerId, {x:e.clientX,y:e.clientY});
      const after = [...pointers.values()].slice(0,2);
      if (after.length === 2 && distance(before) > 0) {
        const rect = surface.getBoundingClientRect();
        x += ((after[0].x+after[1].x)-(before[0].x+before[1].x))/2;
        y += ((after[0].y+after[1].y)-(before[0].y+before[1].y))/2;
        magnify(scale * distance(after)/distance(before), (after[0].x+after[1].x)/2-rect.left-rect.width/2,
          (after[0].y+after[1].y)/2-rect.top-rect.height/2);
      } else { x += e.clientX - previous.x; y += e.clientY - previous.y; render(); }
    });
    for (const type of ['pointerup','pointercancel','lostpointercapture']) {
      surface.addEventListener(type, e => pointers.delete(e.pointerId));
    }
    image.onload = render;
    function open(element) {
      const isSvg = element.localName === 'svg';
      let src;
      if (isSvg) {
        // SVG is loaded as an image, so its scripts and document navigation are disabled.
        const embedded = element.querySelector('image');
        src = embedded?.href?.baseVal;
        if (src) src = new URL(src,element.baseURI).href;
        else {
          ownedUrl = URL.createObjectURL(new Blob([new XMLSerializer().serializeToString(element)],{type:'image/svg+xml'}));
          src = ownedUrl;
        }
      } else src = element.currentSrc || element.src;
      if (!src || !(/^(?:blob:|data:image\/)/i.test(src) || new URL(src,location.href).origin === 'https://book.invalid')) return false;
      previousFocus = element.hasAttribute('tabindex') ? element : element.ownerDocument.activeElement;
      scale = 1; x = 0; y = 0; image.style.transform = '';
      image.alt = element.getAttribute('alt') || element.getAttribute('aria-label') || element.querySelector('title')?.textContent || 'Иллюстрация';
      image.src = src; container.hidden = false;
      document.getElementById('book-turn').setAttribute('inert','');
      container.querySelector('[data-close]').focus();
      send('illustration', {open:true});
      return true;
    }
    return {open,close};
  }

  return {explorer, illustrationViewer, chapterRemainder};
})();
