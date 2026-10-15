import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdir } from 'node:fs/promises'
const { chromium } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE || 'playwright')
const cookies = JSON.parse(process.env.DASHBOARD_SMOKE_COOKIES || '[]')
if (!cookies.length) throw Error('Authenticated session required')
const browser = await chromium.launch({ channel: 'msedge', headless: true })
try {
  await mkdir('test-results/management-live', { recursive: true })
  const context = await browser.newContext(); await context.addCookies(cookies)
  const page = await context.newPage(); page.setDefaultTimeout(10000)
  const failures = []; page.on('pageerror', error => failures.push(error.message))
  await page.route('**/api/**', route => {
    if (route.request().method() !== 'GET') { failures.push('Unexpected business write'); return route.abort() }
    return route.continue()
  })
  for (const width of [1920, 2560]) {
    await page.setViewportSize({ width, height: width === 1920 ? 1080 : 1440 })
    for (const section of ['users', 'audit', 'announcements']) {
      await page.goto(`http://127.0.0.1:5175/admin/${section}`)
      await page.locator('.management-panel').waitFor()
      assert.equal(await page.locator('p.message.error').count(), 0)
      if (section === 'users') {
        for (const name of ['普通学生', '组织者', '管理员']) {
          await page.getByRole('button', { name: new RegExp(`^${name}`) }).click()
          const rows = page.locator('.user-table-row')
          for (const row of await rows.all()) assert.equal(await row.locator('td').nth(1).innerText(), name)
        }
        await page.getByRole('button', { name: /^组织者/ }).click()
      }
      if (section === 'audit') {
        for (const name of ['活动操作', '用户与权限', '名单导出']) {
          await page.getByRole('button', { name, exact: true }).click()
          assert.ok(await page.getByRole('table').count() <= 1)
        }
      }
      if (section === 'announcements') {
        await page.getByRole('button', { name: '发布公告', exact: true }).click()
        await page.getByRole('dialog').getByLabel('标题', { exact: true }).waitFor()
        await page.getByRole('dialog').getByRole('button', { name: '取消', exact: true }).click()
      }
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
      await page.screenshot({ path: `test-results/management-live/${section}-${width}.png`, fullPage: true })
    }
  }
  assert.deepEqual(failures, [])
  console.log('PASS live MySQL: 1080p/2K user identity groups, audit categories, announcement editor, no business writes.')
} finally { await browser.close() }
