import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdir } from 'node:fs/promises'
import { resolve } from 'node:path'
import { createServer } from 'vite'
const require = createRequire(import.meta.url)
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright')
const output = resolve('test-results/rectification')
await mkdir(output, { recursive: true })
const server = await createServer({ server: { host: '127.0.0.1', port: 0 } })
let browser
try {
  await server.listen()
  browser = await chromium.launch({ headless: true, channel: 'msedge' })
  const page = await browser.newPage()
  page.setDefaultTimeout(6000)
  await page.route('https://**/*', route => route.abort())
  const errors = []; page.on('pageerror', error => errors.push(error.message))
  let admin = false, hasAvatar = false, saved = null, creates = 0, submitFails = true, reads = 0
  let notification = { id: 8, title: '活动审核通过', body: '点击查看审核结果', createdAt: '2026-10-15T03:00:00Z', readAt: null, targetPath: '/organizer/10' }
  const activity = { id: 10, title: '通知对应活动', description: '活动正文', status: 'PUBLISHED', organizerId: 1, capacity: 30,
    startsAt: '2027-02-01T06:00:00Z', endsAt: '2027-02-01T08:00:00Z', registrationOpensAt: '2026-10-15T02:00:00Z',
    registrationClosesAt: '2027-02-01T05:30:00Z', checkinOpensAt: '2027-02-01T05:30:00Z', checkinClosesAt: '2027-02-01T06:30:00Z' }
  const account = () => ({ id: 1, name: '整改验收', studentNumber: '20260000000001', roles: [admin ? 'ADMIN' : 'ORGANIZER'], hasAvatar })
  // Every request is mocked, including uploads and writes; no business data is changed.
  await page.route('**/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname
    const json = body => route.fulfill({ json: body })
    if (path === '/api/auth/me') return json(account())
    if (path === '/api/auth/csrf') return json({ token: 'fixture-token' })
    if (path === '/api/account/notifications/unread-count') return json({ count: notification.readAt ? 0 : 1 })
    if (path === '/api/account/notifications') return json([notification])
    if (path === '/api/account/notifications/8/read' && request.method() === 'POST') { reads++; notification.readAt = new Date().toISOString(); return json(notification) }
    if (path === '/api/account/avatar') {
      if (request.method() === 'POST') { hasAvatar = true; return json(account()) }
      if (request.method() === 'DELETE') { hasAvatar = false; return route.fulfill({ status: 204 }) }
      return route.fulfill({ contentType: 'image/png', body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX1sAAAAASUVORK5CYII=', 'base64') })
    }
    if (path === '/api/organizer/activities' && request.method() === 'POST') {
      creates++; saved = { ...request.postDataJSON(), id: 50, status: 'DRAFT' }; return json(saved)
    }
    if (path === '/api/organizer/activities/50' && request.method() === 'PUT') { saved = { ...saved, ...request.postDataJSON() }; return json(saved) }
    if (path === '/api/organizer/activities/50/submit') return submitFails
      ? route.fulfill({ status: 409, json: { message: '测试审核提交失败' } }) : json({ ...saved, status: 'PENDING' })
    if (path === '/api/activities/10') return json(activity)
    if (path === '/api/activities/50') return json(saved)
    if (path.endsWith('/stats')) return json({ registeredCount: 0, checkedCount: 0, absentCount: 0, capacity: 30 })
    if (path.endsWith('/registrations') || path.endsWith('/reviews') || path === '/api/organizer/messages') return json([])
    if (path === '/api/organizer/activities') return json([])
    if (path === '/api/admin/users') return json([])
    if (path === '/api/admin/binding-appeals/availability') return json({ enabled: true })
    if (path === '/api/admin/binding-appeals') return json({ items: [], page: 0, size: 20, total: 0 })
    if (path === '/api/admin/binding-appeals/25') return json({ id: 25, type: 'STUDENT_NUMBER_OCCUPIED', proposedStudentNumber: '20260000000001', status: 'REJECTED', reviewNote: '证件不清晰', evidenceAvailable: false })
    errors.push(`Unexpected request ${request.method()} ${path}`)
    return route.fulfill({ status: 500, json: { message: 'Unconfigured fixture' } })
  })
  const base = `http://127.0.0.1:${server.httpServer.address().port}`
  for (const width of [1920, 2560]) {
    await page.setViewportSize({ width, height: width === 2560 ? 1440 : 1080 })
    await page.goto(base + '/organizer/new')
    await page.getByLabel('活动名称', { exact: true }).fill('时间与提交审核验收')
    await page.getByLabel('地点名称', { exact: true }).fill('校内会场')
    await page.getByLabel('活动开始（北京时间）', { exact: true }).fill('2027-02-01T14:00')
    await page.getByLabel('活动结束（北京时间）', { exact: true }).fill('2027-02-01T16:00')
    await page.getByLabel('报名提前截止（分钟）', { exact: true }).fill('60')
    await page.getByLabel('签到提前开放（分钟）', { exact: true }).fill('15')
    await page.getByLabel('开始后签到截止（分钟）', { exact: true }).fill('45')
    await page.getByText('截止：2027-02-01 13:00', { exact: true }).waitFor()
    await page.getByText('时段：2027-02-01 13:45 至 2027-02-01 14:45', { exact: true }).waitFor()
    await page.getByRole('button', { name: '开始时', exact: true }).click()
    await page.getByText('截止：2027-02-01 14:00', { exact: true }).waitFor()
    await page.getByRole('button', { name: '提前 60 分钟', exact: true }).click()
    await page.getByRole('button', { name: '前后各 30 分钟', exact: true }).click()
    await page.getByText('时段：2027-02-01 13:30 至 2027-02-01 14:30', { exact: true }).waitFor()
    await page.getByLabel('签到提前开放（分钟）', { exact: true }).fill('15')
    await page.getByLabel('开始后签到截止（分钟）', { exact: true }).fill('45')
    await page.getByLabel('活动开始（北京时间）', { exact: true }).fill('2020-01-01T14:00')
    await page.getByText('截止：2020-01-01 13:00（已过期）', { exact: true }).waitFor()
    await page.getByRole('status').filter({ hasText: '当前北京时间' }).waitFor()
    await page.getByLabel('活动开始（北京时间）', { exact: true }).fill('2027-02-01T14:00')
    await page.getByLabel('推文导语', { exact: true }).fill('导语')
    await page.getByLabel('推文正文', { exact: true }).fill('正文')
    await page.getByRole('button', { name: '保存并提交审核', exact: true }).waitFor()
    await page.evaluate(() => window.scrollTo(0, 0))
    await page.screenshot({ path: resolve(output, `organizer-editor-${width}.png`), fullPage: true })
    if (width === 1920) {
      await page.getByRole('button', { name: '保存草稿', exact: true }).click()
      await page.getByText('草稿已保存', { exact: true }).waitFor()
      assert.equal(saved.registrationClosesAt, '2027-02-01T05:00:00.000Z')
      assert.equal(saved.checkinOpensAt, '2027-02-01T05:45:00.000Z')
      assert.equal(saved.checkinClosesAt, '2027-02-01T06:45:00.000Z')
      assert.equal(creates, 1)
    }
    await page.getByRole('button', { name: /通知，/ }).click()
    await page.getByRole('button', { name: /活动审核通过/ }).click()
    await page.waitForURL('**/organizer/10')
    await page.getByRole('heading', { name: '通知对应活动', exact: true }).waitFor()
    await page.evaluate(() => window.scrollTo(0, 0))
    await page.screenshot({ path: resolve(output, `organizer-notification-target-${width}.png`), fullPage: true })
  }
  assert.equal(reads, 1)
  saved.longitude = 108.9; saved.latitude = 34.2
  await page.goto(base + '/organizer/50/edit')
  await page.getByLabel('活动名称', { exact: true }).waitFor()
  await page.getByRole('button', { name: '保存并提交审核', exact: true }).click()
  await page.getByText('提交审核失败：测试审核提交失败，可重试提交', { exact: true }).waitFor()
  await page.getByText('草稿已保存', { exact: true }).waitFor()
  assert.equal(creates, 1)
  submitFails = false
  await page.getByRole('button', { name: '保存并提交审核', exact: true }).click()
  await page.waitForURL('**/organizer')
  assert.equal(creates, 1)
  await page.goto(base + '/account')
  await page.locator('input[type=file]').setInputFiles({ name: 'avatar.png', mimeType: 'image/png', buffer: Buffer.from('fixture') })
  await page.getByText('头像已更新，与小程序共用本人头像', { exact: true }).waitFor()
  assert.equal(hasAvatar, true)
  await page.getByRole('button', { name: '移除头像', exact: true }).click()
  await page.getByText('已恢复默认头像', { exact: true }).waitFor()
  assert.equal(hasAvatar, false)
  admin = true
  notification = { ...notification, title: '账号申诉已驳回', readAt: null, targetPath: '/admin/users?appeal=25' }
  for (const width of [1920, 2560]) {
    await page.setViewportSize({ width, height: width === 2560 ? 1440 : 1080 })
    await page.goto(base + '/account')
    await page.getByRole('button', { name: /通知，/ }).click()
    const panel = page.getByRole('region', { name: '通知列表' })
    const box = await panel.boundingBox()
    assert.ok(box && box.x >= 0 && box.x + box.width <= width)
    await page.evaluate(() => window.scrollTo(0, 0))
    await page.screenshot({ path: resolve(output, `admin-notifications-${width}.png`), fullPage: true })
    await page.getByRole('button', { name: /账号申诉已驳回/ }).click()
    await page.getByRole('dialog').getByText('证件不清晰', { exact: true }).waitFor()
  }
  assert.deepEqual(errors, [])
  console.log('PASS: 1080p/2K editor, derived schedule, save, notification read/navigation, appeal deep link, avatar upload/remove (mocked APIs).')
} finally { await browser?.close(); await server.close() }
