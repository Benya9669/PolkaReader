// node tools/brand/render-previews.cjs <Polka frontend with npm ci>
// Renders the Android XML paths directly and checks them against the upstream SVG.
const fs = require('node:fs');
const path = require('node:path');
const {createRequire} = require('node:module');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../..');
const brand = path.join(root,'design/brand');
const res = path.join(root,'app/src/main/res');
const frontend = path.resolve(process.argv[2] || '.verification/Polka/frontend');
const {chromium} = createRequire(path.join(frontend,'package.json'))('playwright');
const read = file => fs.readFileSync(file,'utf8');

async function main() {
  const executablePath = [process.env.BROWSER_PATH,'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'].find(file => file && fs.existsSync(file));
  const browser = await chromium.launch({executablePath,headless:true});
  try {
    const page = await browser.newPage({viewport:{width:720,height:352},deviceScaleFactor:1});
    const {fallback, samples, difference} = await page.evaluate(async source => {
      const parse = xml => new DOMParser().parseFromString(xml,'text/xml');
      const android = 'http://schemas.android.com/apk/res/android';
      const attr = (e,k) => e.getAttributeNS(android,k);
      const values = new Map([...parse(source.values).documentElement.children].map(n=>[n.getAttribute('name'),n.textContent]));
      const resolve = v => v.startsWith('@') ? values.get(v.split('/')[1]) : v;
      function paths(vector, tint) {
        const group = vector.querySelector('group');
        const content = [...vector.querySelectorAll('path')].map(p => {
          const color = tint || resolve(attr(p,'fillColor'));
          const stroke = attr(p,'strokeColor') ? ' stroke="'+resolve(attr(p,'strokeColor'))+'" stroke-width="'+attr(p,'strokeWidth')+'"' : '';
          return '<path fill="'+color+'" d="'+resolve(attr(p,'pathData'))+'"'+stroke+'/>';
        }).join('');
        return group ? '<g transform="translate('+attr(group,'translateX')+' '+attr(group,'translateY')+') scale('+attr(group,'scaleX')+' '+attr(group,'scaleY')+')">'+content+'</g>' : content;
      }
      const wrap = (box,body) => '<svg xmlns="http://www.w3.org/2000/svg" viewBox="'+box+'">'+body+'</svg>';
      const sourceUrl = x => 'data:image/svg+xml;charset=utf-8,'+encodeURIComponent(x);
      async function raster(svg,size) {
        const documentSvg = parse(svg).documentElement;
        documentSvg.setAttribute('width',size);documentSvg.setAttribute('height',size);
        const image = new Image();image.src = sourceUrl(new XMLSerializer().serializeToString(documentSvg));await image.decode();
        const cv = document.createElement('canvas');cv.width=cv.height=size;
        cv.getContext('2d').drawImage(image,0,0,size,size);
        return cv;
      }
      const mark = wrap('0 0 48 48',paths(parse(source.mark)));
      const original = await raster(source.original,480), converted = await raster(mark,480);
      const pixels = canvas=>canvas.getContext('2d').getImageData(0,0,canvas.width,canvas.height).data;
      const a=pixels(original), b=pixels(converted);
      let changed=0;
      function different(i,j) {
        const alphaA=a[i+3], alphaB=b[j+3];
        if(Math.abs(alphaA-alphaB)>16) return true;
        for(let c=0;c<3;c++) if(Math.abs(a[i+c]*alphaA-b[j+c]*alphaB)>16*255) return true;
        return false;
      }
      for(let y=0;y<480;y++) for(let x=0;x<480;x++) {
        const i=4*(y*480+x);
        if(!different(i,i)) continue;
        changed++;
        // SVG rect and equivalent arc paths can use different edge antialiasing.
        // Interior pixels must coincide, so a broad shape or palette change fails.
        if(x>0 && x<479 && y>0 && y<479) {
          const sameSurface=[i-4,i+4,i-1920,i+1920].every(n=>[0,1,2,3].every(c=>a[n+c]===a[i+c]));
          if(sameSurface) throw Error('Interior color differs at '+x+', '+y);
        }
      }
      const fg=parse(source.color),mono=parse(source.mono);
      const safe=await raster(wrap('0 0 108 108',paths(fg)),540), alpha=pixels(safe);
      for(let y=0;y<540;y++) for(let x=0;x<540;x++) {
        if(alpha[(y*540+x)*4+3]>127 && Math.hypot(x+.5-270,y+.5-270)>165) throw Error('Foreground exceeds 66dp safe circle');
      }
      function masked(shape, themed=false) {
        const bg=themed?'#D6E6D1':values.get('polka_green');
        const drawable=paths(themed?mono:fg,themed?'#354D39':null);
        const mask=shape==='circle'?'<circle cx="54" cy="54" r="36"/>':'<rect x="18" y="18" width="72" height="72" rx="17"/>';
        return wrap('18 18 72 72','<defs><clipPath id="mask">'+mask+'</clipPath></defs><g clip-path="url(#mask)"><rect width="108" height="108" fill="'+bg+'"/>'+drawable+'</g>');
      }
      const fallback={};
      for(const size of [48,72,96,144,192]) fallback[size]=(await raster(masked('square'),size)).toDataURL('image/png').split(',')[1];
      return {difference:changed/(480*480),fallback,
        samples:[masked('circle'),masked('square'),masked('circle',true)].map(sourceUrl)};
    },{values:read(path.join(res,'values/brand.xml')),mark:read(path.join(res,'drawable/polka_mark.xml')),
      color:read(path.join(res,'drawable/polka_launcher_foreground.xml')),mono:read(path.join(res,'drawable/polka_launcher_monochrome.xml')),
      original:read(path.join(brand,'polka-mark.svg'))});
    assert(difference<.005,'Vector conversion changed more than the antialiasing tolerance: '+difference);
    for(const [density,size] of Object.entries({mdpi:48,hdpi:72,xhdpi:96,xxhdpi:144,xxxhdpi:192})) {
      const dir=path.join(res,'mipmap-'+density);fs.mkdirSync(dir,{recursive:true});
      fs.writeFileSync(path.join(dir,'ic_launcher.png'),Buffer.from(fallback[size],'base64'));
    }
    const cases=[['circle','Круглая маска'],['rounded','Скруглённая маска'],['monochrome','Монохромная иконка']];
    for(let i=0;i<cases.length;i++) {
      const [id,title]=cases[i],src=samples[i];
      await page.setContent('<!doctype html><meta charset="utf-8"><style>*{box-sizing:border-box}body{margin:0;background:#f3f0e6;color:#263f31;font:16px "Segoe UI",Arial,sans-serif}header{margin:26px 40px 0;font:35px Georgia,serif}main{display:flex;margin:21px 40px;gap:30px;align-items:center}figure{margin:0;display:grid;justify-items:center;gap:9px}figure:first-child{padding-right:32px;border-right:1px solid #b8c1ae}figcaption{color:#465a4d;font-size:13px}section{display:grid;gap:18px;align-items:start}section p{margin:0;font-size:13px;color:#465a4d}section div{display:flex;gap:15px;align-items:center}strong{font-size:16px}small{display:block;margin-top:2px;font-size:13px;font-weight:400;color:#465a4d}</style><header>'+title+'</header><main><figure><img width="192" height="192" src="'+src+'"><figcaption>Увеличено в 4 раза</figcaption></figure><section><p>Размер на экране при плотности mdpi</p><div><img width="48" height="48" src="'+src+'"><strong>Polka Reader<small>48 × 48 пикселей</small></strong></div><div><img width="32" height="32" src="'+src+'"><strong>Polka Reader<small>32 × 32 пикселя</small></strong></div></section></main>');
      await page.evaluate(()=>Promise.all([...document.images].map(image=>image.decode())));
      await page.screenshot({path:path.join(brand,id+'.png')});
    }
    console.log('PASS upstream mark, adaptive 66dp safe area, mdpi–xxxhdpi fallback and three masks. Pixel mismatch:',difference);
  } finally {await browser.close()}
}
main().catch(error=>{console.error(error);process.exitCode=1});
