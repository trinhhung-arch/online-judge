-- =============================================================================
-- R__ (repeatable) — Dữ liệu tham chiếu: ngôn ngữ chấm và máy chấm chuẩn
--
-- Đây là hiện thân của chỉ số M4 trong nfrplan: "thêm 1 ngôn ngữ chấm =
-- 1 dòng config, 0 dòng code". Thêm ngôn ngữ = thêm một khối VALUES ở dưới.
--
-- Repeatable migration: Flyway chạy lại mỗi khi checksum file đổi, nên sửa hệ
-- số thời gian không cần tạo file V mới. Toàn bộ file phải idempotent.
--
-- ⚠️ Các con số time_multiplier dưới đây là GIÁ TRỊ KHỞI ĐIỂM theo nfrplan 9.2.
--    Chúng chỉ có nghĩa sau khi đo trên máy chấm chuẩn (tuần 4).
-- =============================================================================

-- =============================================================================
-- ★ CỘT `enabled` NẰM TRONG UPSERT NÀY, và đó là một quyết định.
--
-- V8 đã REVOKE INSERT/UPDATE/DELETE trên `languages` khỏi oj_app, nên không đường
-- nào trong ứng dụng bật/tắt được một ngôn ngữ — chỉ migration làm được. File này
-- vì thế là nguồn sự thật duy nhất cho việc ngôn ngữ nào đang mở.
-- =============================================================================
INSERT INTO languages (code, display_name, version_label, source_extension,
                       compile_command, run_command,
                       time_multiplier, startup_overhead_ms, memory_overhead_kb,
                       enabled, sort_order)
VALUES
    -- {pch}: thư mục precompiled header bên trong box (Bước 3.5). Worker gắn nó read-only
    -- và chỉ ở bước biên dịch. Host chưa chạy scripts/build-pch.sh thì thư mục không tồn
    -- tại, GCC bỏ qua -I trong im lặng, và bài vẫn chấm ĐÚNG — chỉ chậm hơn.
    -- Đo trên WSL x86: 2.5s -> 1.0s cho một bài có #include <bits/stdc++.h>.
    ('cpp20', 'C++20', 'GCC 13 / -O2 / PCH bits/stdc++.h', 'cpp',
     'g++ -std=gnu++20 -O2 -pipe -static -I{pch} -o {bin} {src}', '{bin}',
     1.00, 0, 0, TRUE, 10),

    -- ★ TẮT — và đây không phải một lựa chọn về sản phẩm, mà là một sự thật đo được.
    --
    -- `oj.worker.sandbox.run.processes` là MỘT con số chung cho mọi ngôn ngữ, và nó
    -- đang là 1. Javadoc của WorkerProperties.Run tự nói đây là con số tạm của M2 và
    -- M3 phải mang nó vào chính bảng này; M3 không làm, và không có cột nào cho nó.
    --
    -- Đo bằng isolate trên host thật, cùng một chương trình Java:
    --     --processes=1    pthread_create failed (EAGAIN) -> VM không khởi động
    --     --processes=8    OutOfMemoryError: unable to create native thread
    --     --processes=32   chạy được
    --
    -- Nên mọi bài Java sẽ trả RE. Bật một ngôn ngữ không chấm được là nói dối người
    -- nộp bài bằng một dòng trong menu chọn ngôn ngữ — tệ hơn hẳn việc không có nó.
    -- C++ và Python chạy đúng ở processes=1, nên chỉ một trong ba bị tắt.
    --
    -- Mở lại khi: `processes` thành một cột của bảng này và một trường của
    -- JudgeJobDto (đổi oj-contract -> CLAUDE.md mục 5.1, phải hỏi người), CỘNG với
    -- CommandTemplate.resolveProgram dùng toRealPath — '/usr/bin/java' là symlink vào
    -- /etc/alternatives, mà /etc không được mount trong box, nên execve trả ENOENT
    -- trước cả khi chạm giới hạn tiến trình.
    --
    -- HopDongVanHanhTest.ngon_ngu_dang_bat_phai_chay_duoc canh dòng FALSE này.
    ('java21', 'Java 21', 'OpenJDK 21', 'java',
     'javac -encoding UTF-8 -d {dir} {src}',
     'java -Xmx{mem}m -Xss64m -XX:+UseSerialGC -cp {dir} Main',
     2.50, 100, 131072, FALSE, 20),

    ('py311', 'Python 3.11', 'CPython 3.11', 'py',
     NULL, 'python3 {src}',
     4.00, 30, 32768, TRUE, 30)
ON CONFLICT (code) DO UPDATE SET
    display_name        = EXCLUDED.display_name,
    version_label       = EXCLUDED.version_label,
    compile_command     = EXCLUDED.compile_command,
    run_command         = EXCLUDED.run_command,
    time_multiplier     = EXCLUDED.time_multiplier,
    startup_overhead_ms = EXCLUDED.startup_overhead_ms,
    memory_overhead_kb  = EXCLUDED.memory_overhead_kb,
    enabled             = EXCLUDED.enabled,
    sort_order          = EXCLUDED.sort_order,
    updated_at          = now();

-- Máy chấm chuẩn: mọi giới hạn thời gian của đề đều quy chiếu về máy này.
-- host_factor của chính nó luôn = 1.000 theo định nghĩa.
INSERT INTO judge_hosts (name, arch, judge_slots, host_factor, is_reference)
VALUES ('mac-m1max-host', 'arm64', 6, 1.000, TRUE)
ON CONFLICT (name) DO UPDATE SET
    arch        = EXCLUDED.arch,
    judge_slots = EXCLUDED.judge_slots,
    updated_at  = now();

INSERT INTO tags (slug, name) VALUES
    ('dp','Quy hoạch động'), ('graph','Đồ thị'), ('greedy','Tham lam'),
    ('math','Toán'), ('string','Xâu'), ('ds','Cấu trúc dữ liệu'),
    ('sorting','Sắp xếp'), ('binary-search','Chặt nhị phân'), ('implementation','Cài đặt')
ON CONFLICT (slug) DO NOTHING;
