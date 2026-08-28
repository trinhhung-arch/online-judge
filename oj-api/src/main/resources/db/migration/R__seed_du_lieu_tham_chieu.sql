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

INSERT INTO languages (code, display_name, version_label, source_extension,
                       compile_command, run_command,
                       time_multiplier, startup_overhead_ms, memory_overhead_kb, sort_order)
VALUES
    ('cpp20', 'C++20', 'GCC 13 / -O2 / PCH bits/stdc++.h', 'cpp',
     'g++ -std=gnu++20 -O2 -pipe -static -o {bin} {src}', '{bin}',
     1.00, 0, 0, 10),

    ('java21', 'Java 21', 'OpenJDK 21', 'java',
     'javac -encoding UTF-8 -d {dir} {src}',
     'java -Xmx{mem}m -Xss64m -XX:+UseSerialGC -cp {dir} Main',
     2.50, 100, 131072, 20),

    ('py311', 'Python 3.11', 'CPython 3.11', 'py',
     NULL, 'python3 {src}',
     4.00, 30, 32768, 30)
ON CONFLICT (code) DO UPDATE SET
    display_name        = EXCLUDED.display_name,
    version_label       = EXCLUDED.version_label,
    compile_command     = EXCLUDED.compile_command,
    run_command         = EXCLUDED.run_command,
    time_multiplier     = EXCLUDED.time_multiplier,
    startup_overhead_ms = EXCLUDED.startup_overhead_ms,
    memory_overhead_kb  = EXCLUDED.memory_overhead_kb,
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
