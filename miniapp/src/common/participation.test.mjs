import test from 'node:test'
import assert from 'node:assert/strict'
import { searchCheckedRecords } from './participation.js'

const records = [
  { id: 1, activityTitle: '校园讲座', checkedAt: '2026-10-10T10:00:00Z' },
  { id: 2, activityTitle: '校园市集', checkedAt: null },
  { id: 3, activityTitle: '心理辅导', checkedAt: '2026-10-11T10:00:00Z' },
]

test('personal participation only includes checked-in activities', () => {
  assert.deepEqual(searchCheckedRecords(records, '').map(row => row.id), [1, 3])
  assert.deepEqual(searchCheckedRecords(records, '  校园  ').map(row => row.id), [1])
  assert.deepEqual(searchCheckedRecords(records, '市集'), [])
})
