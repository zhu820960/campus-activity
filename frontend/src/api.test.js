import assert from 'node:assert/strict'
import test from 'node:test'
import { apiFile } from './api.js'

test('file download returns the server blob', async () => {
  const originalFetch = globalThis.fetch
  try {
    const payload = new Blob(['xlsx'])
    globalThis.fetch = async () => ({ ok: true, blob: async () => payload })
    assert.equal(await apiFile('/admin/activities/1/exports/checkins'), payload)
  } finally { globalThis.fetch = originalFetch }
})

test('expired session redirects file download back to login', async () => {
  const originalFetch = globalThis.fetch
  const originalWindow = globalThis.window
  let destination = ''
  try {
    globalThis.window = { location: { pathname: '/admin/activities/1', search: '',
      replace: value => { destination = value } } }
    globalThis.fetch = async () => ({ ok: false, status: 401,
      json: async () => ({ message: '请重新登录' }) })
    await assert.rejects(apiFile('/admin/activities/1/exports/checkins'),
      error => error.status === 401 && error.message === '请重新登录')
    assert.equal(destination, '/login?next=%2Fadmin%2Factivities%2F1')
  } finally {
    globalThis.fetch = originalFetch
    globalThis.window = originalWindow
  }
})
