ALTER TABLE contests ADD COLUMN standings_settled_at TIMESTAMPTZ;

UPDATE contests
   SET standings_settled_at = ends_at + interval '5 minutes'
 WHERE ends_at + interval '5 minutes' < now();

COMMENT ON COLUMN contests.standings_settled_at IS
    'Lúc bảng xếp hạng chốt: đã quá standings-grace sau ends_at, không còn bài nào của kỳ thi trong judge_queue, và mọi bài đã chấm đều đã vào bảng. NULL = StandingsUpdater còn cập nhật. Thay cho mốc cứng "ends_at + 5 phút" khiến bài chấm xong muộn không bao giờ vào bảng (BX-07).';
