# 024 · Cache bảng xếp hạng chỉ được ghi khi bảng chưa đổi kể từ lúc đọc

**Bối cảnh.** `RedisStandingsCache.top` hụt cache thì đọc Postgres rồi ghi vào Redis (TTL 60s).
Nếu một lô mới commit và xoá cache **trong lúc** lượt đọc ấy đang chạy, lượt đọc ghi bảng cũ vào
cache: REST trả bảng cũ tới 60s, các luồng SSE vừa nhận sự kiện có thể đọc trúng và đẩy nó cho mọi
người — và ở lô cuối sau giờ kết thúc thì không còn sự kiện nào sửa lại (ca TT-06).

**Quyết định.** Mỗi kỳ thi có khoá `oj:standings:{id}:ver`. `RedisStandingsEventBus.bangDaDoi`
tăng nó **trước** khi xoá cache và phát sự kiện. Lượt đọc nhớ phiên bản trước khi đọc Postgres, rồi
ghi cache bằng một Lua script: phiên bản còn y nguyên thì ghi, đã đổi thì bỏ.

**Lý do.** So-rồi-ghi trong một script là nguyên tử ở Redis, không cần khoá, không đụng Postgres.
Hạ TTL chỉ thu nhỏ khe hở (vẫn vượt P8 2s), không đóng nó. Redis chết thì vẫn rơi về Postgres như cũ.

**Hệ quả chấp nhận.** Mọi chỗ ghi cache bảng xếp hạng về sau phải đi qua cùng script. Khoá phiên
bản không có hạn — vài byte mỗi kỳ thi.
