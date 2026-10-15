import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdir } from 'node:fs/promises'
import { createServer } from 'vite'
const { chromium } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE || 'playwright')
await mkdir('test-results/management', { recursive: true })
const server = await createServer({ server: { host: '127.0.0.1', port: 0 } })
let browser
try {
  await server.listen(); browser = await chromium.launch({ channel: 'msedge', headless: true })
  const page = await browser.newPage(); page.setDefaultTimeout(7000)
  const errors = []; page.on('pageerror', error => errors.push(error.message))
  await page.route('https://**/*', route => route.abort())
  let organizer = false, pending = null, fail = false, writes = []
  let notices = [{ id: 1, title: '秋季活动安排', body: '公告正文。'.repeat(150), active: true, publishedAt: '2026-10-15T02:00:00Z' }]
  const activity = { id: 9, title: '已发布活动', status: 'PUBLISHED', organizerId: 2, description: '活动正文', articleSummary: '活动导语', location: '校内会场', longitude: 108.9, latitude: 34.2, capacity: 30, registeredCount: 5, startsAt: '2027-02-01T06:00:00Z', endsAt: '2027-02-01T08:00:00Z', registrationOpensAt: '2026-10-15T02:00:00Z', registrationClosesAt: '2027-02-01T05:30:00Z', checkinOpensAt: '2027-02-01T05:30:00Z', checkinClosesAt: '2027-02-01T06:30:00Z' }
  await page.route('**/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname, method = request.method()
    const json = body => route.fulfill({ json: body })
    if (path === '/api/auth/me') return json({ id: organizer ? 2 : 1, name: '界面验收', roles: organizer ? ['STUDENT', 'ORGANIZER'] : ['ADMIN'] })
    if (path === '/api/auth/csrf') return json({ token: 'test' })
    if (path === '/api/account/notifications/unread-count') return json({ count: 0 })
    if (path === '/api/account/notifications' || path === '/api/organizer/messages') return json([])
    if (path === '/api/admin/users') return json([
      { id: 1, name: '管理员甲', roles: ['ADMIN'], activityCount: 3 },
      { id: 2, name: '组织者乙', roles: ['STUDENT', 'ORGANIZER'], activityCount: 1, wechatBound: true, bindingManagementEnabled: true, webCredentialsActive: true },
      { id: 3, name: '学生丙', roles: ['STUDENT'], studentNumber: '20260000000001' },
    ])
    if (path.endsWith('/binding-appeals/availability')) return json({ enabled: false })
    if (path.endsWith('/activity-audits/search')) return json({ items: [{ activityId: 9, activityTitle: '已发布活动', action: 'REVIEW_APPROVED', actorName: '管理员甲', createdAt: '2026-10-15T02:00:00Z', detail: '审核通过依据' }], total: 1, page: 0, size: 20 })
    if (path.endsWith('/user-audits/search')) return json({ items: [{ targetId: 2, targetName: '组织者乙', action: 'ORGANIZER_GRANTED', actorName: '管理员甲', createdAt: '2026-10-15T02:00:00Z' }], total: 1, page: 0, size: 20 })
    if (path.includes('export-audits')) return json({ items: [{ id: 1, activityId: 9, activityTitle: '已发布活动', listType: 'REGISTRATIONS', scope: 'ADMIN', actorName: '管理员甲', rowCount: 5, createdAt: '2026-10-15T02:00:00Z' }], total: 1, page: 0, size: 20 })
    if (path.startsWith('/api/admin/announcements')) {
      if (method === 'GET') return json(notices)
      writes.push({ method, path, body: request.postDataJSON() })
      if (method === 'PUT') notices[0] = { ...notices[0], ...request.postDataJSON() }
      if (method === 'DELETE') notices[0].active = false
      if (path.endsWith('/publish')) notices[0].active = true
      return json(notices[0])
    }
    if (path === '/api/organizer/activities') return json([activity])
    if (path === '/api/activities/9') return json(activity)
    if (/\/organizer\/activities\/9\/(changes|cancellation)$/.test(path)) {
      if (method === 'GET') return pending?.kind === path.split('/').at(-1) ? json(pending) : route.fulfill({ status: 200, body: '' })
      writes.push({ method, path, body: request.postDataJSON() })
      if (fail) return route.fulfill({ status: 409, json: { message: '测试申请失败，可重试' } })
      pending = { id: 21, kind: path.split('/').at(-1), status: 'PENDING' }; return json(pending)
    }
    errors.push(`Unexpected ${method} ${path}`); return route.fulfill({ status: 500, json: { message: 'Missing fixture' } })
  })
  const base = `http://127.0.0.1:${server.httpServer.address().port}`
  for (const width of [1920, 2560]) {
    await page.setViewportSize({ width, height: width === 1920 ? 1080 : 1440 })
    organizer = false
    await page.goto(base + '/admin/users')
    await page.locator('.user-table-row').waitFor()
    assert.equal(await page.locator('.user-table-row').count(), 1)
    await page.locator('.user-table-row').getByText('学生丙', { exact: true }).waitFor()
    for (const [tab, name] of [['组织者', '组织者乙'], ['管理员', '管理员甲']]) {
      await page.getByRole('button', { name: new RegExp(`^${tab}`) }).click()
      await page.locator('.user-table-row').getByText(name, { exact: true }).waitFor()
      assert.equal(await page.locator('.user-table-row').count(), 1)
    }
    await page.getByRole('button', { name: /^组织者/ }).click()
    await page.screenshot({ path: `test-results/management/users-${width}.png`, fullPage: true })
    await page.goto(base + '/admin/audit')
    await page.getByRole('cell', { name: '审核通过依据' }).waitFor()
    await page.getByRole('button', { name: '用户与权限', exact: true }).click()
    await page.getByRole('cell', { name: '授予组织者', exact: true }).waitFor()
    assert.equal(await page.getByRole('table').count(), 1)
    await page.getByRole('button', { name: '名单导出', exact: true }).click()
    await page.getByRole('cell', { name: '报名名单', exact: true }).waitFor()
    await page.screenshot({ path: `test-results/management/audit-${width}.png`, fullPage: true })
    await page.goto(base + '/admin/announcements')
    await page.getByRole('button', { name: '查看 / 编辑' }).click()
    const dialog = page.getByRole('dialog')
    await dialog.getByLabel('标题', { exact: true }).fill('公告修改验收')
    await dialog.getByLabel('正文', { exact: true }).fill('修改后的公告正文')
    await dialog.getByRole('button', { name: '预览', exact: true }).click()
    await page.screenshot({ path: `test-results/management/notice-editor-${width}.png`, fullPage: true })
    await dialog.getByRole('button', { name: '保存修改' }).click()
    await dialog.waitFor({ state: 'hidden' })
    page.once('dialog', d => d.accept())
    await page.getByRole('button', { name: '撤回', exact: true }).click()
    await page.getByRole('cell', { name: '已撤回', exact: true }).waitFor()
    page.once('dialog', d => d.accept())
    await page.getByRole('button', { name: '重新发布', exact: true }).click()
    await page.getByRole('cell', { name: '展示中', exact: true }).waitFor()
    await page.screenshot({ path: `test-results/management/notices-${width}.png`, fullPage: true })
    organizer = true; pending = null
    await page.goto(base + '/organizer')
    await page.getByRole('link', { name: '申请修改', exact: true }).click()
    await page.getByLabel('活动名称', { exact: true }).fill('修改后的活动')
    await page.getByLabel('修改原因', { exact: true }).fill('会场安排更新')
    fail = true
    await page.getByRole('button', { name: '提交修改审核' }).click()
    await page.getByText('测试申请失败，可重试', { exact: true }).waitFor()
    fail = false
    await page.getByRole('button', { name: '提交修改审核' }).click()
    await page.getByText('修改申请已提交，审核通过前沿用当前发布内容。').waitFor()
    assert.equal(await page.getByRole('button', { name: '提交修改审核' }).isDisabled(), true)
    const change = writes.filter(w => w.path.endsWith('/changes')).at(-1)
    assert.equal(change.body.proposed.title, '修改后的活动')
    assert.equal(change.body.proposed.registrationOpensAt, activity.registrationOpensAt)
    await page.goto(base + '/organizer')
    await page.getByRole('button', { name: '申请下架', exact: true }).click()
    await page.getByText('已有修改或下架申请待审核，请等待处理后再操作。').waitFor()
    assert.equal(await page.getByRole('button', { name: '提交下架审核' }).isDisabled(), true)
    await page.getByRole('button', { name: '返回', exact: true }).click()
    pending = null
    await page.getByRole('button', { name: '申请下架', exact: true }).click()
    await page.getByLabel('下架原因', { exact: true }).fill('活动安排取消')
    fail = true
    await page.getByRole('button', { name: '提交下架审核' }).click()
    await page.getByText('测试申请失败，可重试', { exact: true }).waitFor()
    fail = false
    await page.getByRole('button', { name: '提交下架审核' }).click()
    await page.getByRole('dialog').waitFor({ state: 'hidden' })
    await page.getByText('下架申请已提交，等待管理员审核；审核通过前活动保持发布状态。').waitFor()
    await page.screenshot({ path: `test-results/management/organizer-${width}.png`, fullPage: true })
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
  }
  assert.equal(writes.filter(w => w.path.endsWith('/cancellation')).length, 4)
  assert.deepEqual(errors, [])
  console.log('PASS 1080p/2K: identity tables, audit categories, announcement edit/withdraw/republish, organizer changes/cancellation requests, pending exclusion and failure retry. All business APIs mocked.')
} finally { await browser?.close(); await server.close() }
