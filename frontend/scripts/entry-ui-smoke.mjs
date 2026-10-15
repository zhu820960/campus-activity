import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdir } from 'node:fs/promises'
import { resolve } from 'node:path'
import { createServer } from 'vite'
import { adminSections } from '../src/adminSections.js'

const require = createRequire(import.meta.url)
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright')
const output = resolve('test-results/entries')
await mkdir(output, { recursive: true })
const server = await createServer({ server: { host: '127.0.0.1', port: 0 } })
let browser
try {
  await server.listen()
  browser = await chromium.launch({ headless: true, channel: 'msedge' })
  const page = await browser.newPage()
  await page.route('https://**/*', route => route.abort())
  page.setDefaultTimeout(6000)
  const failures = []
  page.on('pageerror', error => failures.push(error.message))
  let enabled = true, bound = true, appealFailure = false, organizer = false
  const activity = { id: 10, title: '入口回归活动', status: 'DRAFT', organizerId: 999,
    startsAt: '2027-01-01T10:00:00Z', endsAt: '2027-01-01T12:00:00Z',
    registrationDeadline: '2026-12-31T10:00:00Z', checkinOpensAt: '2027-01-01T09:00:00Z',
    checkinClosesAt: '2027-01-01T12:00:00Z', capacity: 20, registeredCount: 1, location: '校园' }
  // Fail closed: no API requests can reach a real backend, including mutations.
  await page.route('**/api/**', route => {
    const path = new URL(route.request().url()).pathname
    const json = body => route.fulfill({ json: body })
    if (route.request().method() !== 'GET') {
      failures.push(`Unexpected mutation ${path}`)
      return route.fulfill({ status: 500, json: { message: 'Mutation blocked' } })
    }
    if (path === '/api/account/notifications/unread-count') return json({ count: 0 })
    if (path === '/api/account/notifications') return json([])
    if (path === '/api/auth/me') return json({ id: 999, name: '入口测试', roles: [organizer ? 'ORGANIZER' : 'ADMIN'] })
    if (path === '/api/admin/users') return json([
      { id: 1, name: '学生测试', roles: ['STUDENT'], wechatBound: bound, bindingManagementEnabled: enabled },
      { id: 999, name: '管理员测试', roles: ['ADMIN'], wechatBound: true, bindingManagementEnabled: true },
    ])
    if (path.endsWith('/binding-appeals/availability')) return appealFailure
      ? route.fulfill({ status: 503, json: { message: '测试申诉故障' } }) : json({ enabled: false })
    if (path === '/api/admin/activities') return json([{ activity, stats: { capacity: 20, registeredCount: 1, checkedCount: 0 } }])
    if (path === '/api/organizer/activities') return json(['DRAFT', 'REJECTED', 'PUBLISHED', 'PENDING', 'CANCELLED'].map((status, id) => ({ ...activity, id, status })))
    if (path.endsWith('/search') || path === '/api/admin/participation') return json({ items: [], total: 0, page: 0, size: 20 })
    if (path.startsWith('/api/admin/') || path === '/api/organizer/messages') return json([])
    failures.push(`Unexpected API ${path}`)
    return route.fulfill({ status: 500, json: { message: 'Unconfigured fixture' } })
  })
  const base = `http://127.0.0.1:${server.httpServer.address().port}`
  for (const width of [1920, 2560]) {
    await page.setViewportSize({ width, height: width === 2560 ? 1440 : 1080 })
    for (const section of adminSections) {
      await page.goto(base + section.path)
      await page.locator('h1').waitFor()
      await page.getByText('正在加载', { exact: true }).waitFor({ state: 'hidden' })
      if (section.key === 'users') {
        const button = page.getByRole('button', { name: '解除微信绑定', exact: true })
        await button.waitFor()
        assert.equal(await button.count(), 1)
        assert.equal(await button.isEnabled(), true)
        await button.scrollIntoViewIfNeeded()
        const box = await button.boundingBox()
        assert.ok(box.x >= 0 && box.x + box.width <= width, `Unbind overflow ${width}`)
      }
      await page.screenshot({ path: resolve(output, `${section.key}-${width}.png`), fullPage: true })
    }
  }
  for (const state of [{ enabled: false, bound: true }, { enabled: true, bound: false }]) {
    enabled = state.enabled; bound = state.bound
    await page.goto(base + '/admin/users')
    const button = page.getByRole('button', { name: '解除微信绑定', exact: true })
    await button.waitFor()
    assert.equal(await button.isDisabled(), true)
    await page.getByText(bound ? '绑定管理未开启，暂不可解绑' : '当前未绑定微信', { exact: true }).waitFor()
  }
  enabled = true; bound = true; appealFailure = true
  await page.goto(base + '/admin/users')
  await page.getByText('申诉服务加载失败：测试申诉故障').waitFor()
  await page.getByRole('button', { name: '解除微信绑定', exact: true }).click()
  await page.getByRole('dialog').waitFor()
  assert.equal(await page.getByRole('button', { name: '确认解绑', exact: true }).isDisabled(), true)
  await page.getByRole('button', { name: '返回', exact: true }).click()
  organizer = true
  for (const width of [1920, 900, 390]) {
    await page.setViewportSize({ width, height: 1080 })
    await page.goto(base + '/organizer')
    await page.getByRole('button', { name: '提交审核', exact: true }).first().waitFor()
    assert.equal(await page.getByRole('button', { name: '提交审核', exact: true }).count(), 2)
    assert.equal(await page.getByRole('link', { name: '签到与统计', exact: true }).count(), 1)
    assert.equal(await page.getByRole('button', { name: '审核历史', exact: true }).count(), 5)
    await page.screenshot({ path: resolve(output, `organizer-${width}.png`), fullPage: true })
  }
  assert.deepEqual(failures, [])
  console.log('PASS: eight admin pages at 1080p and 2K; organizer at three widths; unbind states, dialog, appeal failure isolation; no backend writes.')
} finally {
  await browser?.close()
  await server.close()
}
