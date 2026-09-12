#!/usr/bin/env python3
"""Kịch bản SSE — nfrplan 2.4 (c): "1000 kết nối SSE đồng thời". Chạy qua `chay.sh sse` và `chay.sh ky-thi`.

    sse-tai.py --base http://10.0.0.5:8080 --contest-id 7 [--so-ket-noi 1000 --giu 180 --json out.json]
    sse-tai.py ... --kich-ban ky-thi --dang-nhap --phien-ra F --san-sang F --su-kien-ra F

★ VÌ SAO KHÔNG DÙNG k6: k6 đọc HẾT thân response rồi mới trả về, mà luồng SSE không bao giờ hết —
nó chỉ đo được "treo tới timeout". Ở đây mỗi kết nối được đọc từng dòng, nên biết CHÍNH XÁC kết
nối nào còn sống, nhận bao nhiêu nhịp tim, và đứt lúc nào. Chỉ dùng thư viện chuẩn: không thêm công cụ.

★ BA CÁCH HỎNG MÀ PHẢI PHÂN BIỆT (nguong.json):
  mở hỏng    — không nhận được 200 + text/event-stream
  đứt        — server đóng TRƯỚC khi hết --giu (không tính lần đóng ở oj.sse.timeout — --giu phải nhỏ hơn)
  thiếu nhịp — còn mở mà nhận < giu/nhịp − 1 dòng ':ping'. Đây là loại nguy hiểm nhất: client
               tưởng đang nghe, thực ra không có gì chảy qua.
Song song, một luồng đọc `GET /api/v1/problems` 5 lần/giây trong lúc giữ kết nối: P1 phải giữ
nguyên khi 1000 luồng đang mở, nếu không thì SSE đang ăn tài nguyên của đường đọc.

★ ky-thi: NGƯỜI XEM ĐĂNG NHẬP, VÀ ĐÓ LÀ THỨ LÀM NÊN PHÉP ĐO P8. Trang thật mở luồng bằng fetch +
Authorization (static/js/sse.js), nên mỗi kết nối mang token của đúng một tài khoản tai-k: máy chủ đọc
thêm dòng và hạng của người xem ở MỖI lần bảng đổi — tải thật, không phải bản ẩn danh rẻ hơn. Và khung
đầu tiên có cuaToi.soBaiDat ≥ 1 là lúc bài AC của tai-k HIỆN LÊN màn hình của tai-k; bao-cao-db.py trừ
judged_at. Đăng nhập một lần ở đây rồi ghi phiên cho k6 dùng lại: hai lần là gấp đôi bcrypt.

★ THÂN CHUNKED. Luồng không có Content-Length nên đến dạng Transfer-Encoding: chunked; dòng kích thước
khúc chen giữa một dòng data: và JSON vỡ. Nhịp tim vẫn đếm đúng, nên kịch bản sse cũ không lộ lỗi này.
"""
import argparse
import asyncio
import io
import ipaddress
import json
import math
import os
import resource
import ssl
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from urllib import error as urlerr, request as urlreq
from urllib.parse import urlsplit

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from nguong_chung import ap_dung, cham, dinh_dang, doc_so, nap_nguong  # noqa: E402

TIMEOUT_SERVER_S = 300      # oj.sse.timeout = 5m trong application.yml
TOC_DO_DOC = 5
LO_BCRYPT = 3               # dưới oj.auth.bcrypt-concurrency (4) — cùng lý do với chung.js
UA = 'oj-tai-trong/1.0'     # User-Agent rỗng hoặc "Python-urllib" là thứ Cloudflare hay chặn
MAT_KHAU = os.environ.get('MAT_KHAU', 'matkhau-dev-123')


def kiem_dich(base):
    """Cùng luật với chung.js: chỉ máy nội bộ, trừ khi gõ lại đúng BASE vào biến cho phép."""
    host = urlsplit(base).hostname or ''
    try:
        noi_bo = ipaddress.ip_address(host).is_private or ipaddress.ip_address(host).is_loopback
    except ValueError:
        noi_bo = host == 'localhost' or host.endswith('.local')
    if not noi_bo and os.environ.get('CHO_PHEP_DICH_CONG_KHAI') != base:
        raise SystemExit(f'BASE {base} không phải máy nội bộ. Cố ý thì đặt CHO_PHEP_DICH_CONG_KHAI={base}')


def phan_vi(xs, q):
    return sorted(xs)[math.ceil(q * len(xs)) - 1] if xs else None


def dang_nhap_mot(base, handle):
    than = json.dumps({'dinhDanh': handle, 'password': MAT_KHAU}).encode()
    for _ in range(6):
        req = urlreq.Request(f'{base}/api/v1/auth/login', data=than, method='POST',
                             headers={'Content-Type': 'application/json', 'User-Agent': UA})
        try:
            with urlreq.urlopen(req, timeout=30) as r:
                d = json.load(r)
            return handle, {'access': d['accessToken'], 'refresh': d['refreshToken'],
                            'het': int(time.time() * 1000) + int(d['expiresIn']) * 1000}
        except urlerr.HTTPError as e:
            if e.code != 429:
                raise SystemExit(f'Đăng nhập {handle} → {e.code}. Chưa seed đủ tài khoản, hoặc BASE trỏ nhầm máy chủ.')
        time.sleep(1)
    raise SystemExit(f'Đăng nhập {handle} vẫn 429 sau 6 lần thử.')


def dang_nhap_het(base, n):
    t0 = time.monotonic()
    with ThreadPoolExecutor(LO_BCRYPT) as ex:
        phien = dict(ex.map(lambda i: dang_nhap_mot(base, f'tai-{i + 1}'), range(n)))
    print(f'  đăng nhập {n} tài khoản qua {base}: {time.monotonic() - t0:.0f}s', flush=True)
    return phien


async def mo(u, duong_dan, headers):
    ctx = ssl.create_default_context() if u.scheme == 'https' else None
    r, w = await asyncio.open_connection(u.hostname, u.port or (443 if ctx else 80), ssl=ctx)
    w.write((f'GET {duong_dan} HTTP/1.1\r\nHost: {u.netloc}\r\nUser-Agent: {UA}\r\n{headers}\r\n').encode())
    await w.drain()
    return r, w


async def doc_header(r):
    dau = await r.readuntil(b'\r\n\r\n')
    dong = dau.decode('latin-1').split('\r\n')
    return int(dong[0].split()[1]), dau.lower()


async def doc_khoi(r, chunked, con):
    """Một khối thân đã bỏ khung chunked. b'' = server đóng; hết giờ giữ thì ném TimeoutError."""
    if not chunked:
        return await asyncio.wait_for(r.read(4096), con)
    dong = await asyncio.wait_for(r.readline(), con)
    co = int(dong.split(b';')[0].strip() or b'0', 16) if dong else 0
    if co == 0:
        return b''
    return (await asyncio.wait_for(r.readexactly(co + 2), con))[:-2]


def da_giai(du_lieu):
    try:
        toi = json.loads(du_lieu).get('cuaToi')
    except ValueError:
        return False
    return bool(toi) and toi.get('soBaiDat', 0) >= 1


async def mot_ket_noi(u, duong_dan, giu, kq, token, handle):
    t0 = time.monotonic()
    ghi = {'handle': handle, 'mo': None, 'dong': None, 'thay_ac': None, 'khung': 0}
    kq['ket_noi'].append(ghi)
    tieu_de = 'Accept: text/event-stream\r\n' + (f'Authorization: Bearer {token}\r\n' if token else '')
    try:
        r, w = await asyncio.wait_for(mo(u, duong_dan, tieu_de), 10)
        ma, dau = await asyncio.wait_for(doc_header(r), 10)
    except Exception:
        kq['mo_hong'] += 1
        return
    if ma != 200 or b'text/event-stream' not in dau:
        kq['mo_hong'] += 1
        w.close()
        return
    kq['mo_duoc'] += 1
    kq['toi_header_ms'].append((time.monotonic() - t0) * 1000)
    ghi['mo'] = time.time()
    chunked, nhip, du, het = b'transfer-encoding: chunked' in dau, 0, b'', t0 + giu
    try:
        while True:
            con = het - time.monotonic()
            if con <= 0:
                break
            try:
                khoi = await doc_khoi(r, chunked, con)
            except asyncio.TimeoutError:
                break
            except (asyncio.IncompleteReadError, ConnectionError, ValueError):
                khoi = b''
            if not khoi:
                kq['dut'] += 1          # server đóng trước khi hết thời gian giữ
                break
            kq['byte'] += len(khoi)
            du += khoi
            *dong, du = du.split(b'\n')
            for d in dong:
                if d.startswith(b':'):
                    nhip += 1
                elif d.startswith(b'data:'):
                    ghi['khung'] += 1
                    if token and ghi['thay_ac'] is None and da_giai(d[5:]):
                        ghi['thay_ac'] = time.time()
    finally:
        ghi['dong'] = time.time()
        kq['nhip'].append(nhip)
        w.close()


async def doc_song_song(u, het, kq):
    while time.monotonic() < het:
        t0 = time.monotonic()
        try:
            r, w = await asyncio.wait_for(mo(u, '/api/v1/problems?size=20', 'Connection: close\r\n'), 5)
            ma, _ = await asyncio.wait_for(doc_header(r), 5)
            await asyncio.wait_for(r.read(), 5)
            w.close()
            if ma == 200:
                kq['doc_ms'].append((time.monotonic() - t0) * 1000)
        except Exception:
            kq['doc_hong'] += 1
        await asyncio.sleep(max(0.0, 1 / TOC_DO_DOC - (time.monotonic() - t0)))


async def chay(a, phien):
    u = urlsplit(a.base)
    duong_dan = f'/api/v1/contests/{a.contest_id}/standings/stream'
    kq = {'mo_duoc': 0, 'mo_hong': 0, 'dut': 0, 'nhip': [], 'toi_header_ms': [], 'doc_ms': [],
          'doc_hong': 0, 'byte': 0, 'ket_noi': []}
    viec = []
    for i in range(a.so_ket_noi):
        h = f'tai-{i + 1}'
        viec.append(asyncio.ensure_future(mot_ket_noi(u, duong_dan, a.giu, kq, phien.get(h, {}).get('access'), h)))
        if (i + 1) % a.toc_do_mo == 0:
            await asyncio.sleep(1)
    han = time.monotonic() + 30
    while kq['mo_duoc'] + kq['mo_hong'] < a.so_ket_noi and time.monotonic() < han:
        await asyncio.sleep(0.2)
    if a.san_sang:
        io.open(a.san_sang, 'w').close()
        print(f'  mở xong {kq["mo_duoc"]} kết nối ({kq["mo_hong"]} hỏng) — báo sẵn sàng', flush=True)
    # Đo đường đọc CHỈ khi mọi kết nối đã mở — trước đó là đo lúc đang dốc lên.
    het_giu_chung = time.monotonic() + max(1.0, a.giu - a.so_ket_noi / a.toc_do_mo)
    viec.append(asyncio.ensure_future(doc_song_song(u, het_giu_chung, kq)))
    await asyncio.gather(*viec)
    return kq


def tham_so():
    p = argparse.ArgumentParser()
    p.add_argument('--base', required=True)
    p.add_argument('--contest-id', required=True, type=int)
    p.add_argument('--so-ket-noi', type=int, default=1000)
    p.add_argument('--giu', type=float, default=180)
    p.add_argument('--nhip-tim', type=float, default=15, help='oj.sse.heartbeat, giây')
    p.add_argument('--toc-do-mo', type=int, default=50, help='kết nối mở mỗi giây')
    p.add_argument('--kich-ban', default='sse', choices=['sse', 'ky-thi'])
    p.add_argument('--dang-nhap', action='store_true', help='kết nối thứ k mang token của tai-k')
    p.add_argument('--phien-ra', help='ghi phiên đăng nhập (0600) cho k6 dùng lại')
    p.add_argument('--san-sang', help='chạm file này khi mọi kết nối đã mở xong')
    p.add_argument('--su-kien-ra', help='JSON từng kết nối: giờ mở, đóng, và giờ thấy bài AC của mình')
    p.add_argument('--json')
    return p.parse_args()


def main():
    a = tham_so()
    a.base = a.base.rstrip('/')
    kiem_dich(a.base)
    if a.giu >= TIMEOUT_SERVER_S:
        raise SystemExit(f'--giu {a.giu}s ≥ oj.sse.timeout {TIMEOUT_SERVER_S}s: server sẽ tự đóng và mọi kết nối bị tính là đứt.')
    mem, cung = resource.getrlimit(resource.RLIMIT_NOFILE)
    can = a.so_ket_noi * 2 + 256
    if mem < can:
        resource.setrlimit(resource.RLIMIT_NOFILE, (min(can, cung), cung))
        if resource.getrlimit(resource.RLIMIT_NOFILE)[0] < can:
            raise SystemExit(f'ulimit -n chỉ {mem}, cần {can}. Nâng: sudo launchctl limit maxfiles 65536 200000')

    phien = dang_nhap_het(a.base, a.so_ket_noi) if a.dang_nhap else {}
    if a.phien_ra:
        with os.fdopen(os.open(a.phien_ra, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), 'w') as fh:
            json.dump(phien, fh)
    kq = asyncio.run(chay(a, phien))
    toi_thieu = math.floor(a.giu / a.nhip_tim) - 1
    so = {
        'sse_mo_duoc': kq['mo_duoc'], 'sse_mo_hong': kq['mo_hong'], 'sse_dut': kq['dut'],
        'sse_thieu_nhip': sum(1 for n in kq['nhip'] if n < toi_thieu),
        'sse_toi_header_p95_ms': phan_vi(kq['toi_header_ms'], 0.95), 'sse_doc_p95_ms': phan_vi(kq['doc_ms'], 0.95),
        'sse_bang_thong_mbps': kq['byte'] * 8 / 1e6 / a.giu,
    }
    d = {'kich_ban': a.kich_ban, 'base': a.base,
         'mo_ta': f'{a.so_ket_noi} kết nối{" đăng nhập" if phien else ""} · giữ {a.giu:g}s · nhịp tim {a.nhip_tim:g}s (cần ≥ {toi_thieu} ping)',
         'metrics': {k: {'values': {'value': v}} for k, v in so.items() if v is not None}}
    nguong = nap_nguong()
    print('\n' + '═' * 78 + f'\n  SSE · {d["mo_ta"]} · {a.base}\n' + '═' * 78)
    dat = True
    for r in ap_dung(nguong['cung'], a.kich_ban, 'sse'):
        v, ok = cham(d['metrics'], r)
        dat = dat and ok is True
        dau = '⚠️  không đo được' if ok is None else ('✅' if ok else '❌')
        print(f'  {r["ma"]:<9} {r["nhan"]:<34} {dinh_dang(v, r["don_vi"]):>10}   {dau}')
    for r in ap_dung(nguong['quan_sat'], a.kich_ban, 'sse'):
        print(f'  {r["ma"]:<9} {r["nhan"]:<34} {dinh_dang(doc_so(d["metrics"], r["metric"], r["thong_ke"]), r["don_vi"]):>10}')
    print(f'  {"—":<9} {"lượt đọc hỏng trong lúc giữ":<34} {kq["doc_hong"]:>10}')
    print('═' * 78 + ('\n  ➜  ĐẠT (phần SSE).\n' if dat else '\n  ➜  KHÔNG ĐẠT (phần SSE).\n'))
    d['dat'] = dat
    if a.json:
        with io.open(a.json, 'w', encoding='utf-8') as fh:
            json.dump(d, fh, ensure_ascii=False, indent=2)
    if a.su_kien_ra:
        with io.open(a.su_kien_ra, 'w', encoding='utf-8') as fh:
            json.dump({'dong_ho': 'time.time() của máy chạy sse-tai.py', 'ket_noi': kq['ket_noi']}, fh)
    return 0 if dat else 1


if __name__ == '__main__':
    sys.exit(main())
