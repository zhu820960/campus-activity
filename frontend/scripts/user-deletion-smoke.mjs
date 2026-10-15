import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdir } from 'node:fs/promises'
import { createServer } from 'vite'
const require = createRequire(import.meta.url)
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright')
await mkdir('test-results/user-deletion', { recursive: true })
const server = await createServer({ server: { host: '127.0.0.1', port: 0 } })
let browser
try {
  await server.listen(); browser = await chromium.launch({ headless: true, channel: 'msedge' })
  const page = await browser.newPage(); page.setDefaultTimeout(6000)
  await page.route('https://**/*', route => route.abort())
  let deleted = 0, failedPreview = false, failedDelete = false
  let accounts = []
  const errors = []; page.on('pageerror', error => errors.push(error.message))
  await page.route('**/api/**', route => {
    const request = route.request(), path = new URL(request.url()).pathname
    const json = body => route.fulfill({ json: body })
    if (path === '/api/auth/me') return json({ id: 1, name: '测试管理员', roles: ['ADMIN'] })
    if (path === '/api/auth/csrf') return json({ token: 'fixture-only-token' })
    if (path === '/api/account/notifications') return json([])
    if (path === '/api/account/notifications/unread-count') return json({ count: 0 })
    if (path === '/api/admin/binding-appeals/availability') return json({ enabled: false })
    if (path === '/api/admin/users') return json(accounts)
    if (path === '/api/admin/users/7/deletion-impact') return failedPreview
      ? route.fulfill({ status: 503, json: { message: '测试影响查询失败' } })
      : json({ id: 7, name: '待删除组织者', activities: 3, registrations: 5, checkins: 2, bindings: 1, messages: 4 })
    if (path === '/api/admin/users/7' && request.method() === 'DELETE') {
      assert.deepEqual(request.postDataJSON(), { confirmUserId: 7 })
      if (failedDelete) return route.fulfill({ status: 409, json: { message: '测试事务失败，已回滚' } })
      deleted++; accounts = accounts.filter(account => account.id !== 7)
      return route.fulfill({ status: 204 })
    }
    errors.push(`Unexpected API ${request.method()} ${path}`); return route.abort()
  })
  const base = `http://127.0.0.1:${server.httpServer.address().port}`
  for (const width of [1920, 2560]) {
    accounts = [{ id: 1, name: '管理员', roles: ['ADMIN'] }, { id: 7, name: '待删除组织者', roles: ['STUDENT', 'ORGANIZER'], activityCount: 3, wechatBound: true, bindingManagementEnabled: true }]
    await page.setViewportSize({ width, height: width === 2560 ? 1440 : 1080 })
    await page.goto(base + '/admin/users')
    await page.getByRole('button', { name: /^组织者/ }).click()
    await page.getByRole('button', { name: '删除用户', exact: true }).waitFor()
    assert.equal(await page.getByRole('button', { name: '删除用户', exact: true }).count(), 1)
    await page.getByRole('button', { name: '删除用户', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: '永久删除用户' })
    await dialog.getByText(/3 场负责活动将移交/).waitFor()
    assert.equal(await dialog.getByRole('button', { name: '永久删除', exact: true }).isDisabled(), true)
    const input = dialog.getByLabel('输入用户ID 7 确认删除', { exact: true })
    await input.fill('8')
    assert.equal(await dialog.getByRole('button', { name: '永久删除', exact: true }).isDisabled(), true)
    await input.fill('7')
    assert.equal(await dialog.getByRole('button', { name: '永久删除', exact: true }).isEnabled(), true)
    await page.screenshot({ path: `test-results/user-deletion/confirmation-${width}.png`, fullPage: true })
    failedDelete = true
    await dialog.getByRole('button', { name: '永久删除', exact: true }).click()
    await dialog.getByText('测试事务失败，已回滚', { exact: true }).waitFor()
    assert.equal(accounts.length, 2)
    failedDelete = false
    await dialog.getByRole('button', { name: '永久删除', exact: true }).click()
    await dialog.waitFor({ state: 'hidden' })
    await page.getByText('用户 #7 已永久删除，历史记录已匿名保留', { exact: true }).waitFor()
    assert.equal(await page.locator('.user-table-row').count(), 0)
  }
  accounts.push({ id: 7, name: '待删除组织者', roles: ['STUDENT'] })
  failedPreview = true
  await page.goto(base + '/admin/users')
  await page.getByRole('button', { name: '删除用户', exact: true }).click()
  await page.getByText('测试影响查询失败', { exact: true }).waitFor()
  assert.equal(await page.getByRole('button', { name: '永久删除', exact: true }).isDisabled(), true)
  assert.equal(deleted, 2)
  assert.deepEqual(errors, [])
  console.log('PASS: 1080p/2K deletion preview, administrator protection, exact ID confirmation, failure preservation/retry and list refresh. APIs fully mocked; no real user deleted.')
} finally { await browser?.close(); await server.close() }
