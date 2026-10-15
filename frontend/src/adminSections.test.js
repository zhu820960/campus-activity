import test from 'node:test'
import assert from 'node:assert/strict'
import { adminSections, adminSectionForPath, legacyAdminPath } from './adminSections.js'

test('admin has eight distinct module routes', () => {
  assert.equal(adminSections.length, 8)
  assert.equal(new Set(adminSections.map(section => section.path)).size, 8)
  for (const section of adminSections) assert.equal(adminSectionForPath(section.path), section)
})

test('activity details stay in activity management and legacy tabs redirect', () => {
  assert.equal(adminSectionForPath('/admin/activities/5/edit').key, 'activities')
  assert.equal(legacyAdminPath('reviews'), '/admin/reviews')
  assert.equal(legacyAdminPath('users'), '/admin/users')
  assert.equal(legacyAdminPath('unknown'), null)
})
