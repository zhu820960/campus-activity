-- Read-only preflight for the proposed identity/appeal migration.
-- Run only against the explicitly selected development database. This file
-- does not authorize or perform any migration, backup, cleanup, or correction.

SELECT DATABASE() AS selected_database, VERSION() AS mysql_version;

SELECT table_name, table_rows
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN ('user_accounts', 'wechat_student_bindings',
    'miniapp_access_tokens', 'registrations', 'checkins', 'activities')
ORDER BY table_name;

-- information_schema.table_rows is approximate for InnoDB; these are exact counts.
SELECT 'user_accounts' AS table_name, COUNT(*) AS exact_rows FROM user_accounts
UNION ALL SELECT 'wechat_student_bindings', COUNT(*) FROM wechat_student_bindings
UNION ALL SELECT 'miniapp_access_tokens', COUNT(*) FROM miniapp_access_tokens
UNION ALL SELECT 'registrations', COUNT(*) FROM registrations
UNION ALL SELECT 'checkins', COUNT(*) FROM checkins
UNION ALL SELECT 'activities', COUNT(*) FROM activities
UNION ALL SELECT 'activity_audits', COUNT(*) FROM activity_audits
UNION ALL SELECT 'user_messages', COUNT(*) FROM user_messages;

SELECT table_name, column_name, column_type, is_nullable, generation_expression
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name IN ('wechat_student_bindings', 'miniapp_access_tokens',
    'registrations', 'checkins', 'activity_audits')
ORDER BY table_name, ordinal_position;

SELECT table_name, index_name, non_unique, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS columns_in_order
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name IN ('wechat_student_bindings', 'registrations', 'checkins')
GROUP BY table_name, index_name, non_unique
ORDER BY table_name, index_name;

SELECT table_name, constraint_name, referenced_table_name
FROM information_schema.referential_constraints
WHERE constraint_schema = DATABASE()
  AND table_name IN ('wechat_student_bindings', 'miniapp_access_tokens',
    'registrations', 'checkins')
ORDER BY table_name, constraint_name;

SELECT 'unrevoked_unexpired_tokens' AS check_name, COUNT(*) AS row_count
FROM miniapp_access_tokens
WHERE revoked_at IS NULL AND expires_at > UTC_TIMESTAMP(6)
UNION ALL
SELECT 'bindings_with_participation', COUNT(DISTINCT b.id)
FROM wechat_student_bindings b
WHERE EXISTS (SELECT 1 FROM registrations r WHERE r.student_id = b.student_id)
  OR EXISTS (SELECT 1 FROM checkins c WHERE c.student_id = b.student_id)
UNION ALL
SELECT 'registrations_with_unknown_wechat_source', COUNT(*) FROM registrations
UNION ALL
SELECT 'checkins_with_unknown_wechat_source', COUNT(*) FROM checkins;

SELECT 'duplicate_wechat_identity' AS check_name, COUNT(*) AS conflicting_groups
FROM (
  SELECT app_id, open_id FROM wechat_student_bindings
  GROUP BY app_id, open_id HAVING COUNT(*) > 1
) AS conflicts
UNION ALL
SELECT 'duplicate_bound_student', COUNT(*) FROM (
  SELECT app_id, student_id FROM wechat_student_bindings
  GROUP BY app_id, student_id HAVING COUNT(*) > 1
) AS conflicts
UNION ALL
SELECT 'binding_without_student', COUNT(*)
FROM wechat_student_bindings b LEFT JOIN user_accounts u ON u.id = b.student_id
WHERE u.id IS NULL
UNION ALL
SELECT 'token_without_binding', COUNT(*)
FROM miniapp_access_tokens t LEFT JOIN wechat_student_bindings b ON b.id = t.binding_id
WHERE b.id IS NULL
UNION ALL
SELECT 'duplicate_activity_registration', COUNT(*) FROM (
  SELECT activity_id, student_id FROM registrations
  GROUP BY activity_id, student_id HAVING COUNT(*) > 1
) AS conflicts
UNION ALL
SELECT 'duplicate_activity_checkin', COUNT(*) FROM (
  SELECT activity_id, student_id FROM checkins
  GROUP BY activity_id, student_id HAVING COUNT(*) > 1
) AS conflicts
UNION ALL
SELECT 'participant_account_without_student_number', COUNT(DISTINCT u.id)
FROM user_accounts u
WHERE u.student_number IS NULL
  AND (EXISTS (SELECT 1 FROM registrations r WHERE r.student_id = u.id)
    OR EXISTS (SELECT 1 FROM checkins c WHERE c.student_id = u.id));

-- A check-in without the same activity/student registration needs manual
-- attribution review; it must not be silently paired during migration.
SELECT 'checkin_without_matching_registration' AS check_name, COUNT(*) AS row_count
FROM checkins c
LEFT JOIN registrations r ON r.activity_id = c.activity_id AND r.student_id = c.student_id
WHERE r.id IS NULL
UNION ALL
SELECT 'checkin_with_cancelled_registration', COUNT(*)
FROM checkins c
JOIN registrations r ON r.activity_id = c.activity_id AND r.student_id = c.student_id
WHERE r.status = 'CANCELLED'
UNION ALL
SELECT 'same_student_bound_to_multiple_wechat_apps', COUNT(*)
FROM (
  SELECT student_id FROM wechat_student_bindings
  GROUP BY student_id HAVING COUNT(DISTINCT app_id) > 1
) AS conflicts;

-- Inspect existing status storage before proposing a new cancellation reason
-- or message kind; no account or OpenID values are returned.
SELECT table_name, column_name, column_type, is_nullable
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND ((table_name = 'activities' AND column_name IN ('status', 'review_note'))
    OR (table_name = 'user_messages' AND column_name IN ('kind', 'event_key')))
ORDER BY table_name, ordinal_position;
