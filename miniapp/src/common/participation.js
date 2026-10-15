export function searchCheckedRecords(records, query) {
  const term = String(query || '').trim().toLocaleLowerCase()
  return records.filter(record => record.checkedAt
    && (!term || String(record.activityTitle || '').toLocaleLowerCase().includes(term)))
}
