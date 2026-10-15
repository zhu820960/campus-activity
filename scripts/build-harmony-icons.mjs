import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { resolve } from 'node:path'
import { createRequire } from 'node:module'
const root = resolve(import.meta.dirname, '..')
const source = resolve(root, 'HarmonyOS_Icons')
const definitions = {
  ArrowLeft: ['ic_public_back.svg'], ArrowRight: ['ic_public_back.svg', 180],
  Bell: ['ic_public_ring.svg'], Bot: ['ic_public_comments.svg'],
  CalendarDays: ['ic_public_calendar.svg'], CalendarPlus: ['ic_public_calendar.svg'],
  CheckCircle2: ['ic_public_ok.svg'], ChevronDown: ['ic_public_arrow_right.svg', 90],
  CircleHelp: ['ic_public_help.svg'], ClipboardCheck: ['ic_public_todo.svg'],
  ClipboardList: ['ic_public_notes.svg'], Copy: ['ic_public_copy.svg'],
  Download: ['ic_public_download.svg'], ImagePlus: ['ic_public_picture.svg'],
  LayoutDashboard: ['ic_public_view_grid.svg'], LogOut: ['ic_public_quit.svg'],
  MapPin: ['ic_public_gps.svg'], Megaphone: ['ic_public_sound.svg'],
  QrCode: ['ic_public_code.svg'], RefreshCw: ['ic_public_refresh.svg'],
  Save: ['ic_public_save.svg'], ScrollText: ['ic_public_history.svg'],
  ShieldCheck: ['ic_public_security.svg'], TicketCheck: ['ic_public_cards.svg'],
  Trash2: ['ic_public_delete.svg'], Unlink2: ['ic_public_unlock.svg'],
  Users: ['ic_public_contacts_group.svg'], UsersRound: ['ic_public_contacts_group.svg'],
  Search: ['ic_public_search.svg'], Close: ['ic_public_close.svg'],
  Scan: ['ic_public_scan.svg'], Clock: ['ic_public_clock.svg'],
  Home: ['ic_public_home.svg'], Person: ['ic_public_contacts.svg'],
  List: ['ic_public_view_list.svg'], Check: ['ic_public_ok.svg'],
}
const mini = {
  bell:'Bell',calendar:'CalendarDays',check:'Check',checkin:'ClipboardCheck',clock:'Clock',
  list:'List',logout:'LogOut',next:['ChevronDown',-90],pin:'MapPin',retry:'RefreshCw',
  scan:'Scan',search:'Search',shield:'ShieldCheck',ticket:'TicketCheck',users:'Users',close:'Close',
  'tab-activities-off':'CalendarDays','tab-activities-on':'CalendarDays',
  'tab-hall-off':'Home','tab-hall-on':'Home','tab-my-off':'Person','tab-my-on':'Person',
}
const webDir=resolve(root,'frontend/src/assets/harmony')
await mkdir(webDir,{recursive:true})
const manifest={web:{},miniapp:{}}
const rendered={}
for(const [name,[file,rotation=0]] of Object.entries(definitions)) {
  const raw=await readFile(resolve(source,file),'utf8')
  if (/<script|<foreignObject|https?:[^"\s]+\.(png|jpg)/i.test(raw)) throw Error('Unsupported icon contents')
  const viewBox=raw.match(/viewBox="([^"]+)"/)[1]
  let body=raw.slice(raw.indexOf('>',raw.indexOf('<svg'))+1,raw.lastIndexOf('</svg>'))
    .replace(/<title>[\s\S]*?<\/title>|<desc>[\s\S]*?<\/desc>|<!--[\s\S]*?-->/g,'')
    .replace(/#000000\b|#000\b|\bblack\b/gi,'currentColor')
  // Keep masks/defs and their white paint intact. Rotate around the native viewBox center.
  const [, ,w,h]=viewBox.split(/\s+/).map(Number)
  if(rotation) body=`<g transform="rotate(${rotation} ${w/2} ${h/2})">${body}</g>`
  // Put tall/narrow source arrows on a square canvas to prevent clipping on rotation.
  const squareBody=w!==h ? `<svg x="${(24-w)/2}" y="${(24-h)/2}" width="${w}" height="${h}" viewBox="${viewBox}" overflow="visible">${body}</svg>` : body
  const normalized=`<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="24" height="24" viewBox="0 0 24 24">${squareBody}</svg>`
  await writeFile(resolve(webDir,name+'.svg'),normalized)
  rendered[name]=normalized
  manifest.web[name]={source:file,rotation}
}
// Miniapp/tabBar PNGs are direct format conversions of the same source SVG geometry.
const {chromium}=createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE || 'playwright')
const browser=await chromium.launch({headless:true,channel:'msedge'})
try {
  const page=await browser.newPage({viewport:{width:96,height:96},deviceScaleFactor:1})
  for(const [name,value] of Object.entries(mini)) {
    const [symbol,rotation=0]=Array.isArray(value)?value:[value]
    const color=name.endsWith('-off')?'#758780':name==='logout'?'#a34538':'#147f74'
    await page.setContent(`<style>html,body{margin:0;background:transparent}svg{width:96px;height:96px;display:block;color:${color};transform:rotate(${rotation}deg)}</style>${rendered[symbol]}`)
    await page.screenshot({path:resolve(root,'miniapp/src/static/icons',name+'.png'),omitBackground:true})
    manifest.miniapp[name]={symbol,color,rotation,source:definitions[symbol][0]}
  }
  const cells=Object.entries(rendered).map(([name,svg])=>`<div>${svg}<span>${name}</span></div>`).join('')
  await page.setViewportSize({width:1000,height:650})
  await page.setContent(`<style>body{font:12px sans-serif;color:#147f74;display:grid;grid-template-columns:repeat(8,1fr);gap:14px;padding:18px}div{display:flex;align-items:center;flex-direction:column;gap:10px;padding:12px;border:1px solid #ddd}svg{width:32px;height:32px}span{color:#333}</style>${cells}`)
  // Atlas uses separate SVG documents to avoid native source ids colliding.
  await page.locator('body').evaluate(el=>{for(const svg of el.querySelectorAll('div>svg')){const img=document.createElement('img');img.width=32;img.height=32;img.src='data:image/svg+xml,'+encodeURIComponent(svg.outerHTML.replaceAll('currentColor','#147f74'));svg.replaceWith(img)}})
  await page.screenshot({path:resolve(root,'backups/harmony-icons-20261015/atlas.png'),fullPage:true})
}finally {await browser.close()}
await writeFile(resolve(root,'backups/harmony-icons-20261015/manifest.json'),JSON.stringify(manifest,null,2))
await writeFile(resolve(webDir,'manifest.json'),JSON.stringify(manifest.web,null,2))
console.log(`Generated ${Object.keys(definitions).length} SVG icons and ${Object.keys(mini).length} miniapp PNGs from HarmonyOS_Icons`)
