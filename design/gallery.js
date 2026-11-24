'use strict';
const concepts = {
  binding: ['Переплёт','Тёплая бумага, тёмно-зелёная обложка и книжная антиква.'],
  ink: ['Чернила','Светлая сетка, синий акцент и компактные карточки файлов.'],
  night: ['Ночная полка','Матовые тёмные страницы, тёплый свет и мягкий контраст.'],
};
const books = [
  {title:'Северный маяк',author:'Вера Ланская',format:'EPUB',year:2024,size:'2,4',book:38,category:'Роман',description:'На отдалённом острове картограф восстанавливает старые морские карты и находит письма смотрителя маяка.'},
  {title:'Линии сада',author:'Анна Белова',format:'EPUB',year:2022,size:'1,7',book:16,category:'Эссе',description:'Записки ботаника о людях, растениях и времени в городском саду.'},
  {title:'Атлас облаков над Невой',author:'Марк Юдин',format:'PDF',year:2023,size:'8,6',book:0,category:'Фотоальбом',description:'Дневник наблюдений за небом и светом над набережными.'},
  {title:'Орбиты',author:'Олег Мирный',format:'EPUB',year:2021,size:'3,2',book:0,category:'Фантастика',description:'История астронома, который проводит последнюю зиму в горной обсерватории.'},
  {title:'Письма с островов',author:'Ася Морозова',format:'EPUB',year:2020,size:'1,9',book:0,category:'Путешествия',description:'Письма о дорогах, маленьких гаванях и домах у воды.'},
  {title:'Полевой дневник',author:'Лев Лесин',format:'PDF',year:2024,size:'4,8',book:0,category:'Наблюдения',description:'Заметки географа о лесных тропах и речных переправах.'},
];
const prose = [
  ['Когда лодка коснулась причала, туман уже расходился. Над водой проступила белая башня, и на мгновение показалось, что она стоит прямо в море.','Вера положила ладонь на мокрые перила. Дерево ещё хранило ночной холод. Где-то за стеной сарая коротко звякнула цепь, и пустая бухта отозвалась эхом.','До маяка вела узкая тропа. На каждом повороте море открывалось заново.'],
  ['Тропа огибала скалу. За ней, под стеной маяка, качалась на ветру старая калитка. Вера придержала её и услышала, как на втором этаже кто-то переставил стул.','Смотритель оставил ключ на подоконнике. Рядом лежала сложенная вчетверо карта с маленьким крестиком в самой глубокой части бухты.'],
  ['Карту она развернула уже в доме. Лист был таким тонким, что сквозь бумагу виднелись тёмные полосы стола. На обороте карандашом стояла дата.','В углу комнаты тикали часы. Вера перевела взгляд с карты на окно и дождалась, пока стрелка сделает ещё один круг.'],
];
// Original, reproducible book-jacket illustrations. No image or font requests leave this file.
function paintCover(index) {
  const cv = document.createElement('canvas'); cv.width=240;cv.height=340;
  const c = cv.getContext('2d'), palettes = [
    ['#244858','#81989a','#dec899','#192e33'],['#4a655e','#bcc5a4','#b0925d','#283e31'],
    ['#d7b49c','#e1d6bd','#947d75','#3a4851'],['#222d45','#57516d','#dd9d5b','#111b31'],
    ['#51636d','#9cafa6','#cba679','#2a4244'],['#727454','#b4b68a','#c5b184','#445039'],
  ], [hi,lo,sun,land]=palettes[index];
  const gradient=c.createLinearGradient(0,0,0,340);gradient.addColorStop(0,hi);gradient.addColorStop(.67,lo);gradient.addColorStop(1,land);
  c.fillStyle=gradient;c.fillRect(0,0,240,340);
  let seed=13823+index*321;function random(){ seed=(1664525*seed+1013904223)>>>0;return seed/4294967296; }
  const line=(a,b,color,width=1)=>{c.strokeStyle=color;c.lineWidth=width;c.beginPath();c.moveTo(...a);c.lineTo(...b);c.stroke()};
  const poly=(points,color)=>{c.fillStyle=color;c.beginPath();points.forEach((p,i)=>i?c.lineTo(...p):c.moveTo(...p));c.closePath();c.fill()};
  const disc=(x,y,r,color)=>{c.beginPath();c.arc(x,y,r,0,2*Math.PI);c.fillStyle=color;c.fill()};
  if(index===0||index===4){
    disc(174,152,26,sun);
    for(let i=0;i<24;i++){const x=random()*210,y=110+random()*74;line([x,y],[x+12+random()*38,y-1], '#e3ddbe5f',1);}
    poly([[0,198],[42,176],[63,191],[96,171],[143,199],[200,181],[240,192],[240,241],[0,241]],'#5e7777');
    c.fillStyle='#47646a';c.fillRect(0,215,240,125);
    for(let i=0;i<160;i++){const x=random()*230,y=216+random()*118;line([x,y],[x+3+random()*29,y-1],random()>.55?'#a4af9e88':'#273e4580',1.3);}
    poly([[0,240],[44,227],[67,235],[97,223],[131,230],[162,223],[205,257],[240,252],[240,340],[0,340]],land);
    if(index===0){poly([[108,217],[115,128],[130,128],[139,219]],'#e6dbb8');poly([[127,129],[131,130],[139,219],[128,217]],'#92a39a');
      c.fillStyle='#213943';c.fillRect(113,122,20,7);c.fillRect(116,113,14,10);poly([[112,113],[123,103],[136,113]],'#9c6249');
      c.fillStyle='#e9c895';c.fillRect(119,116,8,5);c.fillStyle='#657e77';c.fillRect(119,154,5,8);c.fillRect(119,183,5,8);
      poly([[84,208],[98,193],[113,208]],'#995f42');c.fillStyle='#b3b197';c.fillRect(87,208,24,13);
    }
    for(let i=0;i<100;i++){const x=random()*240,y=252+random()*88;line([x,y],[x+random()*18,y-3-random()*9],'#b3ac8644');}
  } else if(index===1||index===5){
    disc(166,157,28,sun);for(let j=0;j<30;j++){let x=random()*256-8,y=315+random()*35;const h=70+random()*118;
      line([x,y],[x-8,y-h],j%2?land:'#768469',2);for(let i=1;i<7;i++){const yy=y-(h/7)*i,xx=x-(8/7)*i,dir=i%2?1:-1;
        c.fillStyle=j%2?'#243f36':'#9aab83';c.beginPath();c.ellipse(xx+dir*6,yy-3,9,3.5,-dir*.6,0,Math.PI*2);c.fill();}}
  } else if(index===2){
    for(let j=0;j<6;j++){const y=125+j*28;for(let i=0;i<7;i++)disc(i*43+random()*7,y+random()*12,22+random()*14,j%2?'#d8d3c1':'#a8b9b1');}
    poly([[0,308],[90,267],[142,276],[240,249],[240,340],[0,340]],land);
  } else {
    for(let i=0;i<180;i++)disc(random()*240,random()*340,random()*1.3,'#f6debb');disc(119,215,48,sun);
    for(let i=0;i<6;i++){c.beginPath();c.ellipse(116,210,130+i*9,16+i*11,-.52,0,Math.PI*2);c.strokeStyle=i%2?'#ded0afaa':'#b58568';c.lineWidth=1;c.stroke();}
  }
  for(let i=0;i<14000;i++){c.fillStyle=random()>.5?'#ffffff0c':'#0000000e';c.fillRect(random()*240,random()*340,1.6,1.2);}
  return cv.toDataURL();
}
const art=books.map((_,i)=>paintCover(i));
const drawings={
  phone:'M8 2h8a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z M10 18h4',
  tablet:'M3 4h18v16H3z M18 11v2',book:'M3 5q5-2 9 1 4-3 9-1v14q-5-2-9 1-4-3-9-1z M12 6v14',
  home:'M3 10l9-7 9 7 M5 9v12h5v-7h4v7h5V9',library:'M4 4h4v16H4z M10 4h3v16h-3z M15 6l4-1 3 14-4 1z',
  device:'M5 14v6h14v-6 M12 3v12m-5-5 5 5 5-5',right:'M9 5l7 7-7 7',left:'M15 5l-7 7 7 7',
  arrow:'M4 12h15m-6-6 6 6-6 6',check:'M3 12l5 5L21 4',saved:'M5 12l3 3 5-6 M14 14l2 2 5-6',
  search:'M15 15l6 6 M17 10a7 7 0 1 1-14 0 7 7 0 1 1 14 0',bookmark:'M6 3h12v18l-6-4-6 4z',
  font:'M3 18l5-13 5 13 M5 14h6 M14 18l3-8 3 8 M15 16h4',file:'M5 2h9l5 5v15H5z M14 2v6h5 M8 14h7 M8 17h5',
  wifi:'M2 8q10-8 20 0 M6 12q6-5 12 0 M9 15q3-2 6 0 M12 18h.1',down:'M5 8l7 7 7-7',
};
function icon(name){return '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="'+drawings[name]+'"/></svg>'}
document.querySelectorAll('[data-icon]').forEach(e=>e.innerHTML=icon(e.dataset.icon));
const jacket=(i)=>'<div class="jacket" aria-hidden="true" style="background-image:url('+art[i]+')"><span>'+books[i].title+'</span></div>';
const button=(what,body,cls='',attr='')=>'<button type="button" class="'+cls+'" data-action="'+what+'" '+attr+'>'+body+'</button>';
const ib=(what,g,label)=>button(what,icon(g),'iconbtn','aria-label="'+label+'" title="'+label+'"');
const brand='<div class="brand"><i class="book-mark"></i>Polka Reader</div>';
const os='<div class="os"><span>10:24</span><span>'+icon('wifi')+'<i></i></span></div>';
const gesture='<div class="gesture"></div>';
const navs=[['home','home','Главная'],['catalog','library','Библиотека'],['offline','device','На устройстве']];
const downloaded=new Set([0,1,2]);
const states={home:{screen:'home'},reading:{screen:'read'},library:{screen:'catalog'}};
Object.values(states).forEach(s=>Object.assign(s,{book:0,page:0,large:false,mark:false,query:'',filter:false,controls:true}));
function nav(active,rail=false){return '<nav class="'+(rail?'rail':'tabs')+'" aria-label="Разделы">'+
  (rail?'<i class="book-mark"></i>':'')+navs.map(([v,ic,l])=>button('nav:'+v,icon(ic)+l,active===v?'active':'')).join('')+
  (rail?'<span class="server-dot online">В сети</span>':'')+'</nav>'}
function listRow(i,selected=false,mini=false){
  const b=books[i];
  return button('pick:'+i,jacket(i)+'<span class="rowcopy"><b>'+b.title+'</b><small>'+b.author+
    (mini?' · '+b.format:'')+'</small></span>'+(mini?icon('right'):'<span class="file-kind">'+b.format+'</span>'),
    mini?'mini-row':'result-row'+(selected?' selected':''));
}
function home(s){const b=books[0];return '<div class="appbar">'+brand+'<span class="server">Моя Полка</span></div>'+
  '<div class="scrollbox home-content"><h3>Продолжить чтение</h3><article class="feature"><div class="feature-main">'+jacket(0)+
  '<div><div class="eyebrow">EPUB · На устройстве</div><h4>'+b.title+'</h4><p>'+b.author+'</p><div class="percent"><span>Глава 4</span><b>38%</b></div>'+
  '<div class="meter" style="--progress:38%"><i></i></div></div></div>'+button('resume',icon('book')+'Продолжить','primary')+'</article>'+
  '<div class="section-title"><strong>На устройстве</strong>'+button('nav:offline','Все '+downloaded.size)+'</div>'+
  '<div class="downloaded-rows">'+[...downloaded].filter(x=>x!==0).slice(0,2).map(i=>listRow(i,false,true)).join('')+'</div></div>'+nav('home')}
function read(s){const pct=38+s.page,idx=s.page%prose.length,b=books[s.book];return (s.controls?
  '<div class="readbar">'+ib('back','left','Назад')+'<b>'+b.title+'</b>'+ib('font','font','Размер текста')+ib('mark',s.mark?'check':'bookmark','Закладка')+'</div>':'')+
  '<article class="reading-page" data-action="panels" tabindex="0" aria-label="Показать или скрыть панели" style="--fontsize:'+(s.large?20:17)+'px">'+
  '<div class="chapter-label">Глава четвёртая</div><h3>У причала</h3>'+prose[idx].map(p=>'<p>'+p+'</p>').join('')+'</article>'+
  (s.controls?'<div class="reader-foot"><div class="foot-nav">'+ib('prev','left','Предыдущая страница')+'<span>Глава 4 · '+pct+'%</span>'+ib('next','right','Следующая страница')+
  '</div><div class="meter" style="--progress:'+pct+'%"><i></i></div><div class="sync">'+icon('saved')+(s.mark?'Закладка сохранена':'Позиция синхронизирована')+'</div></div>':'')}
function matches(s){return books.map((_,i)=>i).filter(i=>(!s.filter||books[i].format==='EPUB')&&
  (s.screen!=='offline'||downloaded.has(i))&& (books[i].title+' '+books[i].author).toLocaleLowerCase('ru').includes(s.query.toLocaleLowerCase('ru')))}
function results(s){const found=matches(s);return found.length?found.map(i=>listRow(i,s.book===i)).join(''):'<p class="empty">Книг не найдено</p>'}
function catalog(s,phone=false){return '<section class="'+(phone?'phone-catalog scrollbox':'catalog')+'"><div class="catalog-head"><h3>'+
  (s.screen==='offline'?'На устройстве':'Библиотека')+'</h3><span class="count">'+(s.screen==='offline'?downloaded.size:books.length)+' книг</span></div>'+
  '<label class="field">'+icon('search')+'<input aria-label="Поиск по названию или автору" placeholder="Название или автор"></label>'+
  button('filter',icon('library')+(s.filter?'Только EPUB':'Все форматы')+icon('down'),'library-filter')+'<div class="results">'+results(s)+'</div></section>'}
function detail(s,phone=false){const i=s.book,b=books[i],local=downloaded.has(i),p=b.book;
  return '<section class="detail'+(phone?' scrollbox':'')+'">'+(phone?ib('back','left','Назад'):'')+
    '<div class="detail-hero">'+jacket(i)+'<div><h3>'+b.title+'</h3><p>'+b.author+'</p><span class="tag">'+(local?icon('check')+'На устройстве':b.category)+'</span></div></div>'+
    '<p class="blurb">'+b.description+'</p><h4>Издание и файлы</h4><p class="volume">Полное издание · '+b.year+' · Русский язык</p>'+
    '<div class="filebox">'+icon('file')+'<span><b>'+b.format+' · '+b.size+' МБ</b><small>Файл № '+(124+i)+'</small></span>'+
    (local?'<small>Скачан</small>':button('download','Скачать'))+'</div>'+
    (i===0?'<div class="filebox">'+icon('file')+'<span><b>FB2 · 1,8 МБ</b><small>Файл № 94 · Оригинал</small></span></div>':'')+
    (p?'<div class="progress"><div class="percent"><span>Глава 4 · '+b.format+'</span><b>'+p+'%</b></div><div class="meter" style="--progress:'+p+'%"><i></i></div></div>':'')+
    button(local?'read':'download',icon(local?'book':'device')+(local?'Открыть книгу':'Скачать '+b.format),'primary')+'</section>'}
function draw(id){const element=document.getElementById(id),s=states[id],wide=id==='library';
  let content;
  if(s.screen==='read')content=read(s);
  else if(s.screen==='home')content=home(s);
  else if(s.screen==='detail'&&!wide)content=detail(s,true)+nav('catalog');
  else if(wide)content='<div class="workspace">'+nav(s.screen,true)+catalog(s)+detail(s)+'</div>';
  else content='<div class="appbar">'+brand+'<span class="server">Моя Полка</span></div>'+catalog(s,true)+nav(s.screen);
  element.innerHTML=os+content+gesture;
  const input=element.querySelector('input');
  if(input){input.value=s.query;input.oninput=()=>{s.query=input.value;element.querySelector('.results').innerHTML=results(s)}}
}
document.querySelectorAll('.device').forEach(element=>{
  element.addEventListener('click',e=>{
    const control=e.target.closest('[data-action]');if(!control)return;
    const s=states[element.id], [action,value]=control.dataset.action.split(':');
    if(action==='nav'){s.screen=value;s.query='';s.filter=false}
    if(action==='pick'){s.book=Number(value);if(element.id!=='library')s.screen='detail'}
    if(action==='resume'){s.book=0;s.screen='read';s.page=0;s.controls=true}
    if(action==='read'){s.screen='read';s.controls=true;s.page=0}
    if(action==='back')s.screen=element.id==='library'?'catalog':s.screen==='detail'?'catalog':'home';
    if(action==='font')s.large=!s.large;
    if(action==='mark')s.mark=!s.mark;
    if(action==='panels')s.controls=!s.controls;
    if(action==='next')s.page=Math.min(62,s.page+1);
    if(action==='prev')s.page=Math.max(0,s.page-1);
    if(action==='filter')s.filter=!s.filter;
    if(action==='download')downloaded.add(s.book);
    draw(element.id);
  });
  element.addEventListener('keydown',e=>{if((e.key==='Enter'||e.key===' ')&&e.target.matches('article[data-action]')){e.preventDefault();e.target.click()}});
});
const grid=document.querySelector('.screens');
function layout(){document.querySelectorAll('.frame-space').forEach(w=>{
  const d=w.firstElementChild;if(!w.clientWidth)return;
  const scale=Math.min(w.clientWidth/d.offsetWidth,1.4);d.style.transform='scale('+scale+')';w.style.height=(d.offsetHeight*scale)+'px';
})}
function setTheme(id){if(!concepts[id])id='binding';document.body.dataset.theme=id;
  document.getElementById('concept').textContent=concepts[id][0];document.getElementById('description').textContent=concepts[id][1];
  document.querySelectorAll('[data-set-theme]').forEach(b=>b.setAttribute('aria-pressed',b.dataset.setTheme===id));layout()}
function mode(active){grid.dataset.mode=active;document.querySelectorAll('button[data-mode]').forEach(b=>b.setAttribute('aria-pressed',b.dataset.mode===active));layout()}
document.querySelectorAll('[data-set-theme]').forEach(b=>b.onclick=()=>{location.hash=b.dataset.setTheme;setTheme(b.dataset.setTheme)});
document.querySelectorAll('button[data-mode]').forEach(b=>b.onclick=()=>mode(b.dataset.mode));
window.addEventListener('hashchange',()=>setTheme(location.hash.slice(1)));
Object.keys(states).forEach(draw);setTheme(location.hash.slice(1));mode('all');
new ResizeObserver(layout).observe(grid);
