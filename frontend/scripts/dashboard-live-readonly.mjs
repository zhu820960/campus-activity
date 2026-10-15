import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdir } from 'node:fs/promises'
const require = createRequire(import.meta.url)
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright')
const cookies = JSON.parse(process.env.DASHBOARD_SMOKE_COOKIES || '[]')
if (!cookies.length) throw Error('Authenticated session not supplied')
const browser = await chromium.launch({ headless: true, channel: 'msedge' })
try {
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } })
  await context.addCookies(cookies)
  const page = await context.newPage()
  page.setDefaultTimeout(8000)
  const failures = []
  await page.route('**/api/**', route => {
    if (route.request().method() !== 'GET') { failures.push('Unexpected non-GET API request'); return route.abort() }
    return route.continue()
  })
  page.on('pageerror', error => failures.push(error.message))
  for (const [name, target, filter] of [
    ['查看活动总数', '/admin/activities'],
    ['查看有效报名', '/admin/participation?status=ACTIVE', ['报名状态', 'ACTIVE']],
    ['查看已签到', '/admin/participation?attendance=CHECKED', ['签到状态', 'CHECKED']],
    ['查看待审核', '/admin/reviews'],
  ]) {
    await page.goto('http://127.0.0.1:5175/admin')
    await page.getByRole('link', { name, exact: true }).click()
    await page.waitForURL('http://127.0.0.1:5175' + target)
    await page.locator('.admin-loading').waitFor({ state: 'hidden' })
    assert.equal(await page.locator('p.message.error').count(), 0)
    if (filter) assert.equal(await page.getByLabel(filter[0], { exact: true }).inputValue(), filter[1])
    console.log(`${name}: PASS (live Web + MySQL, read-only)`)
  }
  await page.goto('http://127.0.0.1:5175/admin')
  await page.getByRole('link', { name: '查看活动总数', exact: true }).waitFor()
  await mkdir('test-results/dashboard-links', { recursive: true })
  await page.screenshot({ path: 'test-results/dashboard-links/live-1920.png', fullPage: true })
  assert.deepEqual(failures, [])
} finally { await browser.close() }
