import assert from 'node:assert/strict'
import { mkdir } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { resolve } from 'node:path'
import { createServer } from 'vite'

const require = createRequire(import.meta.url)
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright')
const root = fileURLToPath(new URL('..', import.meta.url))
const output = resolve(root, 'test-results/appeals')
await mkdir(output, { recursive: true })
const server = await createServer({ root, server: { host: '127.0.0.1', port: 0, open: false } })
let browser
try {
  await server.listen()
  const port = server.httpServer.address().port
  browser = await chromium.launch({ headless: true, channel: 'msedge' })
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
  page.setDefaultTimeout(5000)
  const failures = []
  page.on('pageerror', error => failures.push(error.message))
  let available = true
  let rejectCount = 0
  const pending = [{ id: 1, type: 'WRONG_STUDENT_NUMBER', proposedStudentNumber: '20260000000001',
    reason: '隔离页面测试：原学号填写有误', status: 'PENDING', createdAt: '2026-10-13T01:00:00Z', evidenceAvailable: true }]
  const reviewed = Array.from({ length: 21 }, (_, index) => ({ id: 100 + index,
    type: 'STUDENT_NUMBER_OCCUPIED', proposedStudentNumber: '20269999990001', reason: '隔离页面测试：核对学号',
    status: 'REJECTED', createdAt: '2026-10-12T01:00:00Z', reviewedAt: '2026-10-13T01:00:00Z',
    reviewerName: '测试审核员', reviewNote: '学生证照片不清晰，请重新提交', evidenceAvailable: false }))

  // Every API request is intercepted; this test cannot write to a running backend.
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    const path = url.pathname
    const json = body => route.fulfill({ json: body })
    if (path === '/api/account/notifications/unread-count') return json({ count: 0 })
    if (path === '/api/account/notifications') return json([])
    if (path === '/api/auth/me') return json({ id: 999, name: '隔离测试管理员', roles: ['ADMIN'] })
    if (path === '/api/auth/csrf') return json({ token: 'fixture-only-csrf' })
    if (path === '/api/admin/users') return json([])
    if (path === '/api/admin/binding-appeals/availability') return json({ enabled: available })
    if (path === '/api/admin/binding-appeals') {
      const queue = url.searchParams.get('queue')
      const number = url.searchParams.get('studentNumber') || ''
      const pageNumber = Number(url.searchParams.get('page'))
      const rows = (queue === 'PENDING' ? pending : reviewed).filter(row => row.proposedStudentNumber.includes(number))
      return json({ items: rows.slice(pageNumber * 20, (pageNumber + 1) * 20), total: rows.length, page: pageNumber, size: 20 })
    }
    if (path === '/api/admin/binding-appeals/1/impact') return json({ appealId: 1, targetStudentId: 5,
      requiresActivityOwnershipReview: true,
      accounts: [{ studentId: 5, name: '测试账号', studentNumber: '20260000000001', organizer: true,
        webCredentialsActive: true, activityCount: 1, boundToOtherIdentity: true }],
      rows: [{ registrationId: 1, activityId: 10, activityTitle: '隔离测试活动', sourceStudentId: 4,
        checkinId: null, source: 'UNKNOWN_HISTORICAL', targetHasRegistration: false }] })
    if (path.endsWith('/evidence')) return route.fulfill({ status: 404, json: { message: 'Fixture image unavailable' } })
    if (path === '/api/admin/binding-appeals/1/reject' && route.request().method() === 'POST') {
      assert.equal(route.request().postDataJSON().reason, '请补充清晰学生证照片')
      const appeal = pending.pop()
      Object.assign(appeal, { status: 'REJECTED', reviewerName: '测试审核员', reviewedAt: '2026-10-13T01:00:00Z',
        reviewNote: '请补充清晰学生证照片', evidenceAvailable: false })
      reviewed.unshift(appeal)
      rejectCount++
      return json(appeal)
    }
    failures.push(`Unexpected API: ${route.request().method()} ${path}`)
    return route.fulfill({ status: 500, json: { message: 'Unexpected fixture request' } })
  })

  await page.goto(`http://127.0.0.1:${port}/admin/users`)
  await page.getByRole('button', { name: '账号申诉', exact: true }).click()
  await page.getByRole('button', { name: '核对材料', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByText('隔离测试活动', { exact: false }).waitFor()
  await dialog.getByText('须先核对活动管理权', { exact: false }).waitFor()
  await dialog.getByText('学生证照片暂时无法加载，请稍后重新打开。').waitFor()
  for (const width of [1440, 900]) {
    await page.setViewportSize({ width, height: 1000 })
    const box = await dialog.boundingBox()
    assert.ok(box && box.x >= 0 && box.x + box.width <= width, `Pending dialog overflow at ${width}`)
    await page.screenshot({ path: resolve(output, `pending-${width}.png`), fullPage: true })
  }
  await page.setViewportSize({ width: 1440, height: 1000 })
  await dialog.getByLabel('驳回原因').fill('请补充清晰学生证照片')
  await dialog.getByRole('button', { name: '驳回申诉', exact: true }).click()
  await page.getByText('暂无符合条件的申诉').waitFor()
  assert.equal(rejectCount, 1)
  await page.getByRole('button', { name: '已处理', exact: true }).click()
  await page.getByRole('button', { name: '下一页', exact: true }).click()
  await page.getByText('第 2 页', { exact: true }).waitFor()
  assert.equal(await page.locator('.binding-appeal-row').count(), 2)
  await page.getByLabel('搜索拟绑定学号').fill('20260000000001')
  await page.getByRole('button', { name: '搜索', exact: true }).click()
  await page.getByText('共 1 条', { exact: true }).waitFor()
  assert.equal(await page.locator('.binding-appeal-row').count(), 1)
  await page.getByRole('button', { name: '查看结论', exact: true }).click()
  await dialog.getByText('请补充清晰学生证照片', { exact: true }).waitFor()
  await dialog.getByText('学生证照片已按保留期限清理，审核结论仍保留。').waitFor()
  assert.equal(await dialog.getByRole('button', { name: '驳回申诉', exact: true }).count(), 0)
  for (const width of [1440, 900]) {
    await page.setViewportSize({ width, height: 1000 })
    const box = await dialog.boundingBox()
    assert.ok(box && box.x >= 0 && box.x + box.width <= width, `Dialog overflow at ${width}`)
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
    await page.screenshot({ path: resolve(output, `reviewed-${width}.png`), fullPage: true })
  }
  await dialog.getByRole('button', { name: '返回', exact: true }).click()
  available = false
  await page.reload()
  await page.getByLabel('搜索用户').waitFor()
  assert.equal(await page.getByRole('heading', { name: '账号申诉', exact: true }).count(), 0)
  assert.deepEqual(failures, [])
  console.log('PASS: pending/reviewed queues, rejection history, paging, number search, private image error, read-only result, disabled flag, 1440/900 layout. Fixture APIs only.')
  console.log(`Screenshots: ${output}`)
} finally {
  await browser?.close()
  await server.close()
}
