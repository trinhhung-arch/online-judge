#!/usr/bin/env python3
"""Số liệu THẬT của đường chấm, đếm trên MỌI bài của một lượt đo trong Postgres.

    bao-cao-db.py --json tom-tat.json --kich-ban on-dinh --id-sau 1234 --rut-can-xong 1 \
                  [--t-k6-xong '<now() của DB>' --t-rut-can-xong '<now() của DB>']

`chay.sh` gọi file này sau khi hàng đợi rút cạn. Nó vá số DB vào `metrics` của JSON k6, in bảng
kết luận của CẢ lượt đo, và thoát 1 nếu một ngưỡng cứng trượt hoặc không đo được.

★ VÌ SAO LẤY Ở DB CHỨ KHÔNG LẤY Ở k6
Bản cũ đo verdict ở client: chỉ 10% số bài, cắt ở trần 60s, và làm tròn theo nhịp hỏi
0.25→0.65→1.29→2.31s (verdict thật 1.3s bị ghi 2.31s). Mọi mốc giờ ở đây do server ghi, cho mọi
bài, nên không có lấy mẫu, không có kiểm duyệt, không có làm tròn.

★ LƯỢT ĐO LÀ "--id-sau < id ≤ --id-den VÀ handle tai-*". Lọc theo id chứ không theo giờ: k6 chạy
từ máy khác thì đồng hồ hai máy lệch nhau, còn id do chính Postgres cấp. chay.sh chụp max(id) ngay
trước lượt và ngay sau khi rút cạn. CẬN TRÊN là bắt buộc: thiếu nó thì chạy lại báo cáo của một lượt
cũ sẽ đếm lẫn bài của mọi lượt sau. Cửa sổ được lưu vào JSON, nên chạy lại chỉ cần --json.

Kết nối: OJ_TAI_PSQL (mặc định `docker exec -i oj-postgres psql -U ojuser -d ojdb`). Chỉ SELECT.
"""
import argparse
import io
import json
import os
import shlex
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from nguong_chung import ap_dung, cham, dinh_dang, doc_so, nap_nguong  # noqa: E402
import p8_bang  # noqa: E402

PSQL = os.environ.get('OJ_TAI_PSQL', 'docker exec -i oj-postgres psql -U ojuser -d ojdb')

# Mỗi dòng trả một cặp khoá|giá trị. Giá trị vào qua biến psql (:id_sau, :'t0'), không nối chuỗi.
SQL_CHUNG = r"""
\set ON_ERROR_STOP on
WITH bai AS (
    SELECT s.id, s.created_at, s.judged_at, s.status, s.verdict
      FROM submissions s JOIN users u ON u.id = s.user_id
     WHERE s.id > :id_sau AND s.id <= :id_den AND u.handle LIKE 'tai-%'
), lan_dau AS (
    SELECT DISTINCT ON (r.submission_id) r.submission_id, r.started_at, r.finished_at
      FROM judge_runs r JOIN bai b ON b.id = r.submission_id
     ORDER BY r.submission_id, r.attempt
), trung AS (
    SELECT r.submission_id FROM judge_runs r JOIN bai b ON b.id = r.submission_id
     GROUP BY r.submission_id HAVING count(*) FILTER (WHERE r.verdict <> 'IE') >= 2
)
SELECT 'db', current_database()
UNION ALL SELECT 'so_bai', count(*)::text FROM bai
UNION ALL SELECT 'chua_xong', count(*)::text FROM bai WHERE status <> 'DONE'
UNION ALL SELECT 'da_xong', count(*)::text FROM bai WHERE status = 'DONE'
UNION ALL SELECT 'so_ie', count(*)::text FROM bai WHERE verdict = 'IE'
UNION ALL SELECT 'verdict_p50_ms', (percentile_cont(0.5) WITHIN GROUP
    (ORDER BY extract(epoch FROM judged_at - created_at)) * 1000)::text FROM bai WHERE judged_at IS NOT NULL
UNION ALL SELECT 'verdict_p95_ms', (percentile_cont(0.95) WITHIN GROUP
    (ORDER BY extract(epoch FROM judged_at - created_at)) * 1000)::text FROM bai WHERE judged_at IS NOT NULL
UNION ALL SELECT 'verdict_max_ms', (extract(epoch FROM max(judged_at - created_at)) * 1000)::text FROM bai
UNION ALL SELECT 'cho_p95_ms', (percentile_cont(0.95) WITHIN GROUP
    (ORDER BY extract(epoch FROM l.started_at - b.created_at)) * 1000)::text
    FROM lan_dau l JOIN bai b ON b.id = l.submission_id WHERE l.started_at IS NOT NULL
UNION ALL SELECT 'cham_p95_ms', (percentile_cont(0.95) WITHIN GROUP
    (ORDER BY extract(epoch FROM finished_at - started_at)) * 1000)::text FROM lan_dau WHERE started_at IS NOT NULL
UNION ALL SELECT 'cham_trung', count(*)::text FROM trung
UNION ALL SELECT 'rut_can_dot_bien_ms', (extract(epoch FROM max(judged_at) - min(created_at)) * 1000)::text FROM bai
UNION ALL SELECT 'trai_ghi_nhan_ms', (extract(epoch FROM max(created_at) - min(created_at)) * 1000)::text FROM bai
"""
SQL_RUT_CAN = r"""
UNION ALL SELECT 'so_bai_rut_can', count(*)::text FROM bai
     WHERE judged_at > :'t0'::timestamptz AND judged_at <= :'t1'::timestamptz
UNION ALL SELECT 'giay_rut_can', extract(epoch FROM :'t1'::timestamptz - :'t0'::timestamptz)::text
"""
# ky-thi: bài AC ĐẦU TIÊN trong kỳ thi của mỗi tài khoản — đúng những bài làm đổi bảng (p8_bang.py).
SQL_AC = r"""
\set ON_ERROR_STOP on
SELECT u.handle, extract(epoch FROM min(s.judged_at))
  FROM submissions s JOIN users u ON u.id = s.user_id
 WHERE s.id > :id_sau AND s.id <= :id_den AND u.handle LIKE 'tai-%'
   AND s.contest_id IS NOT NULL AND s.verdict = 'AC' AND s.judged_at IS NOT NULL
 GROUP BY u.handle
"""


def truy_van(id_sau, id_den, t0, t1):
    lenh = shlex.split(PSQL) + ['-X', '-q', '-tA', '-F', '|', '-v', f'id_sau={id_sau}', '-v', f'id_den={id_den}']
    sql = SQL_CHUNG
    if t0 and t1:
        lenh += ['-v', f't0={t0}', '-v', f't1={t1}']
        sql += SQL_RUT_CAN
    kq = subprocess.run(lenh, input=sql + ';\n', capture_output=True, text=True, timeout=120)
    if kq.returncode != 0:
        raise SystemExit(f'psql hỏng ({kq.returncode}): {kq.stderr.strip()}\nLệnh: {PSQL}')
    ra = {}
    for dong in kq.stdout.splitlines():
        if '|' in dong:
            k, v = dong.split('|', 1)
            ra[k] = v if k == 'db' else (float(v) if v != '' else None)
    return ra


def truy_van_ac(id_sau, id_den):
    lenh = shlex.split(PSQL) + ['-X', '-q', '-tA', '-F', '|', '-v', f'id_sau={id_sau}', '-v', f'id_den={id_den}']
    kq = subprocess.run(lenh, input=SQL_AC + ';\n', capture_output=True, text=True, timeout=120)
    if kq.returncode != 0:
        raise SystemExit(f'psql hỏng ({kq.returncode}): {kq.stderr.strip()}')
    return [(h, float(t)) for h, t in (d.split('|', 1) for d in kq.stdout.splitlines() if '|' in d)]


def so_db(tho, nop_202, rut_can_xong, kich_ban, nguong):
    """Từ số thô sang metric có nghĩa. Mỗi dòng ở đây là một định nghĩa, đọc cùng nguong.json."""
    so_bai = tho['so_bai'] or 0
    da_xong = tho['da_xong'] or 0
    # "0 mất trên 0 bài" không phải bằng chứng. Đo 2026-09-11: lượt chết ở setup() in R1/R2 ✅.
    co_bai = so_bai > 0 or nop_202 > 0
    ra = {
        'db_so_bai': so_bai,
        # R1: client được hứa 202 nhiều hơn số bài DB có. Âm (client biết ÍT hơn) là timeout phía
        # client sau khi server đã commit — không mất bài, nên kẹp về 0 và in riêng.
        'db_bai_mat': max(0.0, nop_202 - so_bai) if co_bai else None,
        # Chưa rút cạn xong thì "còn QUEUED" là chậm, không phải kẹt: không đo được, không bịa số.
        'db_chua_xong': tho['chua_xong'] if rut_can_xong and co_bai else None,
        'db_cham_trung': tho['cham_trung'] if co_bai else None,
        'db_ti_le_ie': (tho['so_ie'] / da_xong) if da_xong else None,
        'db_verdict_p50_ms': tho['verdict_p50_ms'],
        'db_verdict_p95_ms': tho['verdict_p95_ms'],
        'db_verdict_max_ms': tho['verdict_max_ms'],
        'db_cho_p95_ms': tho['cho_p95_ms'],
        'db_cham_p95_ms': tho['cham_p95_ms'],
        # P5 chỉ có nghĩa ở đợt dồn; ở kịch bản khác nó là độ dài cả lượt đo, và ghi vào JSON là gây nhầm.
        'db_rut_can_dot_bien_ms': tho['rut_can_dot_bien_ms'] if rut_can_xong and kich_ban == 'dot-bien' else None,
    }
    toi_thieu = nguong['rut_can_toi_thieu']
    if (tho.get('giay_rut_can') or 0) >= toi_thieu['giay'] and (tho.get('so_bai_rut_can') or 0) >= toi_thieu['bai']:
        ra['db_thong_luong_bai_s'] = tho['so_bai_rut_can'] / tho['giay_rut_can']
    # Nhịp API đưa bài tới câu INSERT trong đợt dồn. 500 request cùng một mili-giây thì p95 của POST
    # ≈ 0,95 × 500 ÷ nhịp này — lý do P2 ở dot-bien là quan sát chứ không phải ngưỡng. created_at là
    # now() = lúc transaction nộp bài BẮT ĐẦU, nên commit và publish RabbitMQ không nằm trong số này.
    trai_ms = tho.get('trai_ghi_nhan_ms') or 0
    if kich_ban == 'dot-bien' and so_bai >= toi_thieu['bai'] and trai_ms > 0:
        ra['db_ghi_nhan_bai_s'] = (so_bai - 1) / (trai_ms / 1000)
    return {k: {'values': {'value': v}} for k, v in ra.items() if v is not None}


def in_bang(d, nguong, kich_ban, ten_db):
    m = d['metrics']
    print('\n' + '═' * 78)
    print(f'  KẾT LUẬN · {kich_ban.upper()} · {d.get("mo_ta", "")}')
    print(f'  đường API: k6 ở client · đường chấm: đếm mọi bài trong database "{ten_db}"')
    print('═' * 78 + '\n')
    dat_ca = True
    for r in ap_dung(nguong['cung'], kich_ban):
        v, dat = cham(m, r)
        dat_ca = dat_ca and dat is True
        dau = '⚠️  không đo được' if dat is None else ('✅' if dat else '❌')
        moc = '= 0' if r['toi_da'] == 0 else f'< {dinh_dang(r["toi_da"], r["don_vi"])}'
        print(f'  {r["ma"]:<9} {r["nhan"]:<34} {dinh_dang(v, r["don_vi"]):>12}   {moc:<12} {dau}')
    print('\n  QUAN SÁT')
    for r in ap_dung(nguong['quan_sat'], kich_ban):
        v = doc_so(m, r['metric'], r['thong_ke'])
        moc = f'   mốc {dinh_dang(r["moc"], r["don_vi"])}' if 'moc' in r else ''
        print(f'  {r["ma"]:<9} {r["nhan"]:<30} {dinh_dang(v, r["don_vi"]):>12}{moc}')
    return dat_ca


def canh_bao(d, nguong, nop_202):
    m = d['metrics']
    cham95 = doc_so(m, 'db_cham_p95_ms', 'value')
    if cham95 is not None and cham95 < nguong['cham_san_ms']:
        print(f'\n  ⛔ thời gian CHẤM p95 = {cham95:.0f}ms, dưới sàn {nguong["cham_san_ms"]}ms: không có gì được biên dịch.')
        print('     NGUON_DUY_NHAT=0 (CompileCache trả lời) hoặc worker chạy ScriptedJudgeRunner.')
        print('     Mọi số ĐƯỜNG CHẤM ở trên không dùng được. Đường API vẫn đúng.')
    so_bai = doc_so(m, 'db_so_bai', 'value') or 0
    if doc_so(m, 'may_cham_song', 'min') == 0:
        if so_bai and doc_so(m, 'db_chua_xong', 'value') == 0:
            print('\n  ⚠️  mayChamSong = 0 nhưng mọi bài vẫn có verdict: worker KHÔNG gửi phép đo máy (chế độ giả')
            print('     lập, hoặc tên máy không có trong judge_hosts). Số đường chấm không đại diện máy chấm chuẩn.')
        else:
            print('\n  ⛔ Có lúc KHÔNG còn máy chấm nào sống — đường chấm đo một hệ thống không chấm bài.')
    if so_bai > nop_202:
        print(f'\n  ℹ️  DB có {so_bai:.0f} bài, client chỉ nhận {nop_202:.0f} lần 202: phần chênh là request')
        print('     client bỏ cuộc sau khi server đã commit. Không mất bài, nhưng máy đo đã nghẽn.')


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--json', required=True)
    p.add_argument('--kich-ban', required=True, choices=['quet', 'on-dinh', 'dot-bien', 'ky-thi'])
    p.add_argument('--id-sau', type=int)
    p.add_argument('--id-den', type=int)
    p.add_argument('--rut-can-xong', type=int, choices=[0, 1], default=0)
    p.add_argument('--t-k6-xong')
    p.add_argument('--t-rut-can-xong')
    p.add_argument('--sse-json', help='ky-thi: gộp số của sse-tai.py vào kết luận')
    p.add_argument('--su-kien-sse', help='ky-thi: file sự kiện của sse-tai.py, để tính P8')
    a = p.parse_args()

    nguong = nap_nguong()
    with io.open(a.json, encoding='utf-8') as fh:
        d = json.load(fh)
    nop_202 = doc_so(d['metrics'], 'nop_202', 'count') or 0
    cua_so = d.get('cua_so', {})
    id_sau = a.id_sau if a.id_sau is not None else cua_so.get('id_sau')
    id_den = a.id_den if a.id_den is not None else cua_so.get('id_den')
    if id_sau is None or id_den is None:
        raise SystemExit('Thiếu cửa sổ lượt đo: cần --id-sau và --id-den (hoặc JSON đã có "cua_so").')
    tho = truy_van(int(id_sau), int(id_den), a.t_k6_xong, a.t_rut_can_xong)
    # Xoá số DB của lần chạy trước trên cùng file: update() không xoá khoá mà lần này không sinh ra.
    d['metrics'] = {k: v for k, v in d['metrics'].items() if not k.startswith('db_')}
    d['metrics'].update(so_db(tho, nop_202, a.rut_can_xong == 1, a.kich_ban, nguong))
    if a.sse_json:
        with io.open(a.sse_json, encoding='utf-8') as fh:
            d['metrics'].update(json.load(fh)['metrics'])
    lag_am = 0
    if a.su_kien_sse:
        p8, lag_am = p8_bang.tinh(a.su_kien_sse, truy_van_ac(int(id_sau), int(id_den)))
        d['metrics'].update({k: {'values': {'value': v}} for k, v in p8.items() if v is not None})

    dat = in_bang(d, nguong, a.kich_ban, tho['db'])
    canh_bao(d, nguong, nop_202)
    if lag_am:
        print(f'\n  ⚠️  {lag_am} bài có P8 ÂM (thấy trên bảng TRƯỚC judged_at): đồng hồ máy chạy sse-tai.py lệch')
        print('     đồng hồ API, hoặc phiên SSE bị gán nhầm tài khoản. Các bài ấy không vào phân vị P8.')
    print('\n' + '═' * 78)
    print('  ➜  ĐẠT.' if dat else '  ➜  KHÔNG ĐẠT — xem dòng ❌ / ⚠️ ở trên.')
    print('═' * 78 + '\n')

    d['dat'] = dat
    d['cua_so'] = {'id_sau': int(id_sau), 'id_den': int(id_den)}
    d['db'] = tho['db']
    with io.open(a.json, 'w', encoding='utf-8') as fh:
        json.dump(d, fh, ensure_ascii=False, indent=2)
    return 0 if dat else 1


if __name__ == '__main__':
    sys.exit(main())
