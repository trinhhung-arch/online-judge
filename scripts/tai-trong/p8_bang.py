import io
import json
import math

CHO_S = 15

def phan_vi(xs, q):
    return sorted(xs)[math.ceil(q * len(xs)) - 1] if xs else None

def tinh(duong_dan, bai_ac):
    with io.open(duong_dan, encoding='utf-8') as fh:
        ket_noi = {k['handle']: k for k in json.load(fh)['ket_noi']}
    lag, am, thieu, khong = [], 0, 0, 0
    for handle, j in bai_ac:
        k = ket_noi.get(handle)
        if not k or k['mo'] is None or k['mo'] > j:
            khong += 1
        elif k['thay_ac'] is not None:
            if k['thay_ac'] < j:
                am += 1
            else:
                lag.append((k['thay_ac'] - j) * 1000)
        elif k['dong'] >= j + CHO_S:
            thieu += 1
        else:
            khong += 1
    co_ket_luan = len(lag) + thieu > 0
    return {
        'sse_p8_p50_ms': phan_vi(lag, 0.5), 'sse_p8_p95_ms': phan_vi(lag, 0.95),
        'sse_p8_max_ms': max(lag) if lag else None, 'sse_p8_mau': len(lag) if bai_ac else None,
        'sse_thieu_cap_nhat': thieu if co_ket_luan else None,
        'sse_khong_theo_doi': khong if bai_ac else None,
    }, am
