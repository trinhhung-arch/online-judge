-- =============================================================================
-- 12 ca kiểm chứng schema. Chạy: psql -d ojdb -f docs/sql/smoke_test.sql
--
-- ⚠️ BA CA CẦN MIGRATION CỦA MỐC SAU, và file này TỰ BỎ QUA chúng nếu bảng chưa
--    tồn tại. Không có phần bỏ qua đó thì ở M1 script chết ngay tại TEST 9
--    (ON_ERROR_STOP đang bật), và TEST 12 — một ca quan trọng — không bao giờ chạy.
--
--      TEST 1-8, 12   V1-V3   → chạy được ngay ở M1   (9/12 ca)
--      TEST 9         V8      → ai_quota_usage        (tuần 14-15)
--      TEST 10        V7      → jobs                  (M6)
--      TEST 11        V5      → audit_log             (M4)
--
-- Ba ca KỲ VỌNG BÁO LỖI — đó mới là kết quả đúng: TEST 1, TEST 10, TEST 12.
-- =============================================================================
\set ON_ERROR_STOP on
\echo '--- seed ---'
INSERT INTO users (handle, display_name, role) VALUES ('setter1','Setter',  'SETTER');
INSERT INTO users (handle, display_name)            VALUES ('hung','Hung');
INSERT INTO problems (code,title,statement_md,statement_hash,time_limit_ms,memory_limit_kb,owner_id,status,published_at,current_testdata_version)
VALUES ('A-PLUS-B','A+B','Tinh a+b', repeat('a',64), 1000, 262144, 1, 'PUBLISHED', now(), 1);
INSERT INTO testdata_versions (problem_id,version,manifest_sha256,test_count,total_bytes,created_by)
VALUES (1,1,repeat('b',64),3,1024,1);
INSERT INTO testcases (problem_id,testdata_version,ordinal,is_sample,input_sha256,output_sha256,input_bytes,output_bytes) VALUES
 (1,1,1,TRUE ,repeat('1',64),repeat('2',64),4,2),
 (1,1,2,FALSE,repeat('3',64),repeat('4',64),9,3),
 (1,1,3,FALSE,repeat('5',64),repeat('6',64),9,3);
INSERT INTO sample_testcase_contents (testcase_id,input_text,output_text) VALUES (1,'1 2','3');

\echo '--- TEST 1: khong the luu noi dung cho testcase AN (phai loi) ---'
\set ON_ERROR_STOP off
INSERT INTO sample_testcase_contents (testcase_id,input_text,output_text) VALUES (2,'bi mat','bi mat');
\set ON_ERROR_STOP on

\echo '--- TEST 2: nop bai + enqueue ---'
INSERT INTO source_blobs (sha256,content,byte_size) VALUES (repeat('c',64),'int main(){}',12) ON CONFLICT DO NOTHING;
INSERT INTO submissions (user_id,problem_id,language_id,source_sha256,source_bytes,testdata_version)
VALUES (2,1,(SELECT id FROM languages WHERE code='cpp20'),repeat('c',64),12,1);
INSERT INTO judge_queue (submission_id,priority,attempt) VALUES (1,0,0);

\echo '--- TEST 3: claim -> attempt = 1 ---'
WITH picked AS (
  SELECT submission_id FROM judge_queue WHERE claimed_at IS NULL
  ORDER BY priority, enqueued_at, submission_id LIMIT 1 FOR UPDATE SKIP LOCKED)
UPDATE judge_queue q SET claimed_at=now(), lease_until=now()+interval '120 seconds',
       claimed_by_host=1, attempt=q.attempt+1
  FROM picked p WHERE q.submission_id=p.submission_id
RETURNING q.submission_id, q.attempt;
UPDATE submissions SET status='JUDGING', attempt=1 WHERE id=1;

\echo '--- TEST 4: reaper thu hoi (gia lap lease het han) ---'
UPDATE judge_queue SET lease_until = now() - interval '1 second' WHERE submission_id=1;
UPDATE judge_queue SET claimed_at=NULL, lease_until=NULL, claimed_by_host=NULL
 WHERE claimed_at IS NOT NULL AND lease_until < now() RETURNING submission_id, attempt;
UPDATE submissions SET status='QUEUED' WHERE id=1;

\echo '--- TEST 5: claim lai -> attempt = 2 ---'
WITH picked AS (
  SELECT submission_id FROM judge_queue WHERE claimed_at IS NULL
  ORDER BY priority, enqueued_at, submission_id LIMIT 1 FOR UPDATE SKIP LOCKED)
UPDATE judge_queue q SET claimed_at=now(), lease_until=now()+interval '120 seconds',
       claimed_by_host=1, attempt=q.attempt+1
  FROM picked p WHERE q.submission_id=p.submission_id
RETURNING q.submission_id, q.attempt;

\echo '--- TEST 6: worker CU (attempt=1) tra ket qua muon -> phai 0 dong ---'
DELETE FROM judge_queue WHERE submission_id=1 AND attempt=1 RETURNING submission_id;

\echo '--- TEST 7: worker DUNG (attempt=2) ghi ket qua -> 1 dong ---'
DELETE FROM judge_queue WHERE submission_id=1 AND attempt=2 RETURNING submission_id;
INSERT INTO judge_runs (submission_id,attempt,host_id,host_factor,language_id,testdata_version,
                        verdict,score,max_score,failed_test_ordinal,tests_run,time_ms,memory_kb)
VALUES (1,2,1,1.000,(SELECT id FROM languages WHERE code='cpp20'),1,'WA',0,100,2,2,23,15360)
ON CONFLICT DO NOTHING;
UPDATE submissions SET status='DONE',attempt=2,verdict='WA',score=0,max_score=100,
       failed_test_ordinal=2,time_ms=23,memory_kb=15360,judged_at=now() WHERE id=1;

\echo '--- TEST 8: giao trung lan 2 (RabbitMQ at-least-once) -> 0 dong, khong ghi de ---'
DELETE FROM judge_queue WHERE submission_id=1 AND attempt=2 RETURNING submission_id;
SELECT id,status,verdict,attempt FROM submissions WHERE id=1;

SELECT to_regclass('ai_quota_usage') IS NOT NULL AS co_bang \gset
\if :co_bang
\echo '--- TEST 9: quota AI 5/ngay -> lan thu 6 phai 0 dong ---'
DO $$
DECLARE r INT; i INT;
BEGIN
  FOR i IN 1..6 LOOP
    INSERT INTO ai_quota_usage (user_id,usage_date,used_count) VALUES (2,CURRENT_DATE,1)
    ON CONFLICT (user_id,usage_date) DO UPDATE SET used_count = ai_quota_usage.used_count+1
    WHERE ai_quota_usage.used_count < 5
    RETURNING used_count INTO r;
    RAISE NOTICE 'lan % -> %', i, COALESCE(r::text,'TU CHOI (het quota)');
    r := NULL;
  END LOOP;
END $$;
\else
\echo '>>> BO QUA — bang ai_quota_usage chua ton tai (can V8)'
\endif

SELECT to_regclass('jobs') IS NOT NULL AS co_bang \gset
\if :co_bang
\echo '--- TEST 10: chi mot job REJUDGE dang song ---'
INSERT INTO jobs (type,status,params) VALUES ('REJUDGE','RUNNING','{"problemId":1}');
\set ON_ERROR_STOP off
INSERT INTO jobs (type,status,params) VALUES ('REJUDGE','PENDING','{"problemId":1}');
\set ON_ERROR_STOP on
\else
\echo '>>> BO QUA — bang jobs chua ton tai (can V7)'
\endif

SELECT to_regclass('audit_log') IS NOT NULL AS co_bang \gset
\if :co_bang
\echo '--- TEST 11: audit_log dinh dung partition thang ---'
INSERT INTO audit_log (actor_id,actor_role,action,entity_type,entity_id,detail)
VALUES (1,'SETTER','PROBLEM_TESTDATA_REPLACED','problem',1,'{"from":1,"to":2}');
SELECT tableoid::regclass AS partition, action FROM audit_log;
\else
\echo '>>> BO QUA — bang audit_log chua ton tai (can V5)'
\endif

\echo '--- TEST 12: khong the danh dau DONE ma khong co verdict (CHECK) ---'
\set ON_ERROR_STOP off
INSERT INTO submissions (user_id,problem_id,language_id,source_sha256,source_bytes,status)
VALUES (2,1,1,repeat('c',64),12,'DONE');
\set ON_ERROR_STOP on
