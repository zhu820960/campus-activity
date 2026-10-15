import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { createServer } from 'vite'
const {chromium}=createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE || 'playwright')
const server=await createServer({server:{host:'127.0.0.1',port:0}})
let browser
try {
  await server.listen();browser=await chromium.launch({headless:true,channel:'msedge'})
  const page=await browser.newPage({viewport:{width:1920,height:1080}})
  const errors=[];page.on('pageerror',e=>errors.push(e.message))
  await page.route('**/api/**',route=>{
    const path=new URL(route.request().url()).pathname
    if(path==='/api/auth/me')return route.fulfill({json:{id:1,name:'图标检查',roles:['ADMIN']}})
    if(path==='/api/account/notifications/unread-count')return route.fulfill({json:{count:0}})
    return route.fulfill({json:[]})
  })
  await page.goto(`http://127.0.0.1:${server.httpServer.address().port}/admin`)
  await page.getByRole('link',{name:'查看活动总数',exact:true}).waitFor()
  const check=await page.locator('svg.harmony-icon').evaluateAll(icons=>{
    const ids=icons.flatMap(svg=>[...svg.querySelectorAll('[id]')].map(el=>el.id))
    const broken=[]
    for(const svg of icons){
      for(const ref of svg.querySelectorAll('[href],[xlink\\:href]')){
        const id=(ref.getAttribute('href')||ref.getAttribute('xlink:href')||'').slice(1)
        if(id&&!svg.contains(document.getElementById(id)))broken.push(id)
      }
    }
    return {count:icons.length,ids:ids.length,unique:new Set(ids).size,broken,
      invisible:icons.filter(svg=>svg.getBoundingClientRect().width===0).length}
  })
  assert.ok(check.count>=15)
  assert.equal(check.ids,check.unique,'SVG source IDs must be isolated per instance')
  assert.deepEqual(check.broken,[])
  // Mobile-only navigation and the collapsed brand remain intentionally hidden on desktop.
  assert.ok(check.count-check.invisible>=15)
  assert.deepEqual(errors,[])
  console.log(`PASS ${check.count} Harmony SVG instances: isolated defs/masks, valid local references and visible sizes`)
}finally{await browser?.close();await server.close()}
