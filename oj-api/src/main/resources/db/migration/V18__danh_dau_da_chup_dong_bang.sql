ALTER TABLE contests ADD COLUMN frozen_captured_at TIMESTAMPTZ;

UPDATE contests c
   SET frozen_captured_at = f.luc
  FROM (SELECT contest_id, min(captured_at) AS luc
          FROM contest_standings_frozen
         GROUP BY contest_id) f
 WHERE f.contest_id = c.id;

COMMENT ON COLUMN contests.frozen_captured_at IS
    'Lúc chụp bản đóng băng. NULL = chưa chụp. Đánh dấu riêng thay vì dựa vào việc contest_standings_frozen đã có dòng: bảng rỗng lúc freeze_at cũng là một bản chụp, nếu không nó bị chụp lại mỗi nhịp và kết quả sau giờ đóng băng lọt vào (DB-06).';
