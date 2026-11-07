#!/usr/bin/env python3
import io
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from nguong_chung import ap_dung, cham, dinh_dang, doc_so, nap_nguong

def nap(thu_muc):
    ra = []
    for f in sorted(os.listdir(thu_muc)):
        if f.startswith('tom-tat-') and f.endswith('.json'):
            with io.open(os.path.join(thu_muc, f), encoding='utf-8') as fh:
                d = json.load(fh)
            if d.get('kich_ban') == 'quet':
                ra.append((int(d['nguoi']), d))
    return sorted(ra, key=lambda x: x[0])

def dat_o(run, nguong):
    if 'dat' in run:
        return run['dat'] is True
    return all(cham(run['metrics'], r)[1] is True for r in ap_dung(nguong['cung'], 'quet'))

def main():
    thu_muc = sys.argv[1] if len(sys.argv) > 1 else '.'
    nguong = nap_nguong()
    lan = nap(thu_muc)
    if not lan:
        print(f'Không thấy tom-tat-*.json của kịch bản quét trong {thu_muc}')
        return 1

    rong = 34 + 14 * len(lan)
    print('\n' + '═' * rong + '\n  TỔNG HỢP QUÉT — chịu tải được tới đâu\n' + '═' * rong)
    print('  ' + 'người ảo'.ljust(32) + ''.join(str(n).rjust(14) for n, _ in lan))
    print('  ' + '-' * (rong - 2))
    for r in ap_dung(nguong['cung'], 'quet'):
        o = []
        for _, run in lan:
            v, dat = cham(run['metrics'], r)
            o.append(f'{dinh_dang(v, r["don_vi"])} {"·" if dat is None else ("✓" if dat else "✗")}'.rjust(14))
        print('  ' + f'{r["ma"]} {r["nhan"]}'[:32].ljust(32) + ''.join(o))
    print()
    for r in ap_dung(nguong['quan_sat'], 'quet'):
        o = [dinh_dang(doc_so(run['metrics'], r['metric'], r['thong_ke']), r['don_vi']).rjust(14)
             for _, run in lan]
        print('  ' + f'{r["ma"]} {r["nhan"]}'[:32].ljust(32) + ''.join(o))

    moi = [n for n, _ in lan]
    dat = [n for n, run in lan if dat_o(run, nguong)]
    tien_to = []
    for n in moi:
        if n not in dat:
            break
        tien_to.append(n)
    le = sorted(set(dat) - set(tien_to))

    print('\n' + '═' * rong)
    if not tien_to:
        print(f'  ➜  KHÔNG đạt ngay ở mức thấp nhất ({moi[0]} người ảo). Xem dòng ✗ và · ở trên.')
    else:
        con = [n for n in moi if n > tien_to[-1]]
        print(f'  ➜  ĐẠT tới {tien_to[-1]} người ảo (đường API + 0 mất bài + 0 chấm trùng + IE).')
        print(f'     Vỡ từ {con[0]} người.' if con else '     Chưa chạm trần — đo tiếp mức cao hơn.')
    if le:
        print(f'     ⚠️  Mức {le} đạt trong khi mức thấp hơn trượt — gần như luôn là nhiễu. Chạy lại riêng.')
    print('═' * rong)
    mc = nguong['may_chuan']
    print(f'\n  Đường API vỡ → thiếu CPU cho JVM, hoặc hết Hikari pool ({mc["hikari_app_pool"]} connection).')
    print(f'  Hàng đợi dài → {mc["judge_slot"]} judge slot × ~{mc["throughput_cham_uoc_tinh"]} bài/s là trần'
          ' đã biết của máy chấm, không sửa được bằng cách sửa API.\n')
    return 0

if __name__ == '__main__':
    sys.exit(main())
