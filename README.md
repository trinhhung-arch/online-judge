# online-judge

Hệ thống chấm bài lập trình tự động. Người dùng nộp mã nguồn, hệ thống biên dịch và chạy mã đó
trong sandbox, so kết quả với bộ test và trả về verdict (`AC`, `WA`, `TLE`, `MLE`, `RE`, `CE`).

Ba ưu tiên, theo thứ tự: **công bằng** · **không mất bài nộp** · **an toàn** khi chạy mã của
người lạ.

## Tính năng

- **Nộp bài và chấm** C++, Python, Java; xem tiến độ và verdict theo thời gian thực.
- **Đề bài:** soạn đề Markdown, nạp testdata bằng ZIP, subtask, nhiều kiểu checker, chấm lại.
- **Kỳ thi** ICPC và IOI: bảng xếp hạng thời gian thực, đóng băng bảng cuối giờ.
- **Tài khoản:** đăng nhập JWT, phân quyền USER / SETTER / ADMIN, xác thực hai lớp cho ADMIN.
- **Vận hành:** trang trạng thái, nhật ký kiểm toán, chế độ bảo trì.

## Kiến trúc

```
trình duyệt ──▶ oj-api ──▶ PostgreSQL (nguồn sự thật, kể cả hàng đợi chấm)
                  │   └──▶ Redis (cache, pub/sub cho cập nhật thời gian thực)
                  ▼
              RabbitMQ (chỉ để đánh thức)
                  ▼
              oj-worker ──▶ isolate (sandbox: cgroups + namespaces)
```

- **oj-api** — modular monolith (Spring Boot): `identity`, `problems`, `judging`, `contests`.
- **oj-worker** — máy chấm, không có database; nhận việc qua API, chạy bài trong `isolate`.
- **oj-contract** — hợp đồng dữ liệu giữa API và worker.

**Công nghệ:** Java 21 · Spring Boot 4.1 · PostgreSQL 16 · Redis · RabbitMQ · MinIO · isolate ·
Testcontainers · ArchUnit.

## Chạy thử trên máy dev

Cần JDK 21 và Docker.

```bash
cp .env.example .env
docker compose up -d
./mvnw verify
( set -a && . ./.env && set +a && ./scripts/chay-dev.sh api )
```

API chạy ở `http://localhost:8080`. Worker cần Linux để chạy `isolate`:

```bash
sudo ./scripts/build-isolate.sh
( set -a && . ./.env && set +a && ./scripts/chay-dev.sh worker )
```

⛔ `chay-dev.sh` bật profile `dev` và nạp tài khoản mẫu — không chạy nó trên máy prod.

## Kiểm thử

- `./mvnw verify` chạy unit test và integration test trên PostgreSQL, Redis thật (Testcontainers).
- CI trên GitHub Actions: build và test · 14 bài tấn công sandbox · quét lỗ hổng thư viện ·
  dựng ảnh worker.

## Tài liệu

| Tài liệu | Nội dung |
|---|---|
| [`docs/chi-tiet.md`](docs/chi-tiet.md) | hướng dẫn chạy và deploy đầy đủ, trạng thái, số đo |
| [`docs/frplan.md`](docs/frplan.md) | chức năng |
| [`docs/nfrplan.md`](docs/nfrplan.md) | hiệu năng, bảo mật, độ tin cậy |
| [`docs/postgres-design.md`](docs/postgres-design.md) | thiết kế database |
| [`docs/adr/`](docs/adr/) | các quyết định kiến trúc |
| [`CLAUDE.md`](CLAUDE.md) | quy tắc khi sửa code |
