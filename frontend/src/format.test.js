import test from 'node:test'
import assert from 'node:assert/strict'
import { personInitial } from './format.js'

test('record initials skip development labels and fall back for empty names', () => {
  assert.equal(personInitial('[DEV TEST] 学生甲'), '学')
  assert.equal(personInitial('李同学'), '李')
  assert.equal(personInitial(''), '用')
})
