import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdir } from 'node:fs/promises'
import { resolve } from 'node:path'
import { createServer } from 'vite'
const require = createRequire(import.meta.url)
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright')
const output = resolve('test-results/dashboard-links')
await mkdir(output, { recursive: true })
const server = await createServer({ server: { host: '127.0.0.1', port: 0 } })
let browser
try {
  await server.listen()
  browser = await chromium.launch({ headless: true, channel: 'msedge' })
  const page = await browser.newPage()
  page.setDefaultTimeout(6000)
  const errors = [], participationRequests = []
  page.on('pageerror', error => errors.push(error.message))
  await page.route('https://**/*', route => route.abort())
  const now = Date.now()
  const rows = ['DRAFT', 'PENDING', 'PUBLISHED', 'REJECTED', 'CANCELLED'].map((status, index) => ({
    activity: { id: index + 1, title: `入口活动${index + 1}`, organizerName: '测试组织者', status,
      startsAt: new Date(now + (index === 0 ? 86400000 : -86400000)).toISOString(),
      endsAt: new Date(now + (index === 0 ? 90000000 : -82800000)).toISOString(), location: '校内' },
    stats: { registeredCount: 1, checkedCount: 1, capacity: 20 },
  }))
  await page.route('**/api/**', route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname
    const json = body => route.fulfill({ json: body })
    if (request.method() !== 'GET') { errors.push(`Unexpected mutation: ${path}`); return route.abort() }
    if (path === '/api/auth/me') return json({ id: 99, name: '卡片验收', roles: ['ADMIN'] })
    if (path === '/api/account/notifications') return json([])
    if (path === '/api/account/notifications/unread-count') return json({ count: 0 })
    if (path === '/api/admin/activities') return json(rows)
    if (path === '/api/admin/metrics/daily') return json([{ day: '2026-10-15', registered: 2, checked: 1 }])
    if (path === '/api/admin/participation') {
      participationRequests.push(Object.fromEntries(url.searchParams))
      return json({ items: [], total: 0, page: 0, size: 20 })
    }
    if (path.endsWith('/pending')) return json([])
    errors.push(`Unexpected request: ${path}`)
    return route.fulfill({ status: 500, json: { message: 'Unconfigured fixture' } })
  })
  const base = `http://127.0.0.1:${server.httpServer.address().port}`
  async function dashboard() {
    await page.goto(base + '/admin')
    await page.getByRole('link', { name: '查看活动总数', exact: true }).waitFor()
  }
  for (const width of [1920, 2560]) {
    await page.setViewportSize({ width, height: width === 2560 ? 1440 : 1080 })
    for (const [label, target, filter] of [
      ['查看活动总数', '/admin/activities'],
      ['查看有效报名', '/admin/participation?status=ACTIVE', ['报名状态', 'ACTIVE']],
      ['查看已签到', '/admin/participation?attendance=CHECKED', ['签到状态', 'CHECKED']],
      ['查看待审核', '/admin/reviews'],
    ]) {
      await dashboard()
      await page.getByRole('link', { name: label, exact: true }).click()
      await page.waitForURL(base + target)
      if (filter) {
        assert.equal(await page.getByLabel(filter[0], { exact: true }).inputValue(), filter[1])
        await page.getByText('暂无符合条件的参与记录', { exact: false }).waitFor()
        const query = participationRequests.at(-1)
        assert.equal(query[label === '查看有效报名' ? 'status' : 'attendance'], filter[1])
        assert.equal(query.page, '0')
      }
    }
    for (const [phase, label] of [['UPCOMING', '未开始'], ['ONGOING', '进行中'], ['ENDED', '已结束']]) {
      await dashboard()
      await page.getByRole('link', { name: `查看${label}活动`, exact: true }).click()
      await page.waitForURL(base + '/admin/activities?phase=' + phase)
      assert.equal(await page.getByLabel('筛选时间阶段', { exact: true }).inputValue(), phase)
      assert.equal(await page.locator('.admin-event').count(), phase === 'UPCOMING' ? 1 : phase === 'ENDED' ? 4 : 0)
    }
    for (const [status, label] of [['DRAFT', '草稿'], ['PENDING', '待审核'], ['PUBLISHED', '已发布'], ['REJECTED', '已驳回'], ['CANCELLED', '已取消']]) {
      await dashboard()
      await page.getByRole('link', { name: `查看${label}活动`, exact: true }).click()
      await page.waitForURL(base + '/admin/activities?status=' + status)
      assert.equal(await page.getByLabel('筛选审核状态', { exact: true }).inputValue(), status)
      assert.equal(await page.locator('.admin-event').count(), 1)
    }
    await dashboard()
    assert.equal(await page.locator('.participation-row.dashboard-link').getAttribute('href'), '/admin/activities/3')
    await page.getByRole('link', { name: '查看近七日报名与签到的参与记录', exact: true }).click()
    await page.waitForURL(base + '/admin/participation')
    assert.equal(await page.getByLabel('报名状态', { exact: true }).inputValue(), '')
    assert.equal(await page.getByLabel('签到状态', { exact: true }).inputValue(), '')
    await dashboard()
    await page.screenshot({ path: resolve(output, `dashboard-${width}.png`), fullPage: true })
  }
  await page.getByRole('link', { name: '查看活动总数', exact: true }).press('Enter')
  await page.waitForURL(base + '/admin/activities')
  assert.deepEqual(errors, [])
  console.log('PASS: 1080p/2K dashboard cards, 3 phase filters, 5 status filters, participation API filters/reset, popular activity links and keyboard navigation; all API requests read-only and mocked.')
} finally { await browser?.close(); await server.close() }
