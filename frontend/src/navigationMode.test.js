import test from 'node:test'
import assert from 'node:assert/strict'
import { navigationMode } from './navigationMode.js'

test('web navigation only exposes administrator and organizer workspaces', () => {
  assert.equal(navigationMode(['STUDENT']), 'NONE')
  assert.equal(navigationMode(['STUDENT', 'ORGANIZER']), 'ORGANIZER')
  assert.equal(navigationMode(['ORGANIZER']), 'ORGANIZER')
  assert.equal(navigationMode(['STUDENT', 'ORGANIZER', 'ADMIN']), 'ADMIN')
})
