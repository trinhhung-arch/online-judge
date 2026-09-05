#!/usr/bin/env python3
"""So các mức với nhau và chỉ ra ĐIỂM VỠ.

    python3 tong-hop.py /tmp/oj-tai-trong

`chay.sh` gọi file này ở cuối. Chạy tay cũng được — nó chỉ đọc các file
`tom-tat-<mức>.json` mà k6 đã ghi, không gọi mạng, không đụng database.

★ VÌ SAO CẦN MỘT BƯỚC RIÊNG THAY VÌ ĐỌC NĂM BẢNG CỦA k6
Năm bảng rời cho năm câu trả lời rời. Câu hỏi thật không phải "400 người có đạt
không" mà là "đạt tới đâu thì thôi" — và câu đó chỉ hiện ra khi năm mức nằm
cạnh nhau trên cùng một bảng. Thêm nữa, đọc cột `doc_ms` theo chiều dọc cho
biết độ trễ tăng TUYẾN TÍNH hay tăng VỌT; hai hình dạng ấy có hai nguyên nhân
khác nhau (thiếu CPU · hết connection pool) và bảng rời không cho thấy điều đó.

Ngưỡng lấy từ `nguong.json` — cùng một file mà `ket-luan.js` dùng. Không có con
số nào viết cứng trong file này.
"""
import io
import json
import os
import sys

O_KEY = {'ms': lambda v: f'{v:,.0f} ms'.replace(',', ' '),
         'ti_le': lambda v: f'{v * 100:.2f} %',
         'so': lambda v: f'{v:,.0f}'.replace(',', ' ')}


def dinh_dang(v, don_vi):
    if v is None:
        return '—'
    return O_KEY.get(don_vi, O_KEY['so'])(v)


def doc_so(metrics, ten, thong_ke, mac_dinh=None):
    """Trả None khi vắng mặt — xem javadoc `docSo` trong ket-luan.js, cùng lý do."""
    m = metrics.get(ten)
    if not m or 'values' not in m:
        return mac_dinh
    v = m['values'].get(thong_ke)
    return mac_dinh if v is None else v


def nap(thu_muc):
    """Đọc mọi tom-tat-*.json, xếp theo số người tăng dần."""
    ra = []
    for f in os.listdir(thu_muc):
        if not (f.startswith('tom-tat-') and f.endswith('.json')):
            continue
        with io.open(os.path.join(thu_muc, f), encoding='utf-8') as fh:
            d = json.load(fh)
        ra.append((int(d.get('nguoi') or f[8:-5]), d))
    return sorted(ra, key=lambda x: x[0])


def main():
    thu_muc = sys.argv[1] if len(sys.argv) > 1 else '/tmp/oj-tai-trong'
    nguong = json.load(io.open(
        os.path.join(os.path.dirname(os.path.abspath(__file__)), 'nguong.json'),
        encoding='utf-8'))
    lan = nap(thu_muc)
    if not lan:
        print(f'Không thấy tom-tat-*.json nào trong {thu_muc}')
        return 1

    cot = [f'{n}' for n, _ in lan]
    vach = '═' * (34 + 14 * len(cot))
    print()
    print(vach)
    print('  TỔNG HỢP — chịu tải được tới đâu')
    print(vach)
    print()
    print('  ' + 'người ảo'.ljust(32) + ''.join(c.rjust(14) for c in cot))
    print('  ' + '-' * (32 + 14 * len(cot)))

    # --- ngưỡng cứng: đây là phần quyết định ---------------------------------
    for d in nguong['cung']:
        o = []
        for _, run in lan:
            v = doc_so(run['metrics'], d['metric'], d['thong_ke'], d.get('mac_dinh'))
            dau = '·' if v is None else ('✓' if v <= d['toi_da'] else '✗')
            o.append(f'{dinh_dang(v, d.get("don_vi"))} {dau}'.rjust(14))
        print('  ' + f'{d["ma"]} {d["nhan"]}'.ljust(32) + ''.join(o))

    print()
    # --- quan sát: in ra để đọc đường cong, không chấm ------------------------
    for d in nguong['quan_sat']:
        o = [dinh_dang(doc_so(run['metrics'], d['metric'], d['thong_ke'],
                              d.get('mac_dinh')), d.get('don_vi')).rjust(14)
             for _, run in lan]
        print('  ' + f'{d["ma"]} {d["nhan"]}'.ljust(32) + ''.join(o))

    # --- kết luận ------------------------------------------------------------
    def dat_o(run):
        for d in nguong['cung']:
            v = doc_so(run['metrics'], d['metric'], d['thong_ke'], d.get('mac_dinh'))
            if v is None or v > d['toi_da']:
                return False
        return True

    moi = [n for n, _ in lan]
    dat = [n for n, run in lan if dat_o(run)]

    # "Chịu tới N" phải là một TIỀN TỐ LIÊN TỤC, không phải max(dat). Nếu 100 và
    # 200 đạt, 400 trượt, 500 lại đạt thì trả lời "chịu tới 500" là sai — cái 500
    # ấy gần như chắc chắn là nhiễu (hàng đợi mức trước chưa rút cạn, hoặc máy đã
    # throttle rồi hồi lại). Lấy tiền tố, rồi nói riêng về chỗ không đơn điệu.
    tien_to = []
    for n in moi:
        if n in dat:
            tien_to.append(n)
        else:
            break
    khong_don_dieu = sorted(set(dat) - set(tien_to))

    print()
    print(vach)
    if not tien_to:
        print(f'  ➜  KHÔNG đạt ngay ở mức thấp nhất ({moi[0]} người ảo).')
        print('     Kiểm MÁY ĐO trước khi kết luận về máy chủ: `dropped_iterations`')
        print('     khác 0 nghĩa là chính k6 hụt hơi, và khi đó bảng trên vô nghĩa.')
    else:
        cao = tien_to[-1]
        con_lai = [n for n in moi if n > cao]
        print(f'  ➜  ĐẠT tới {cao} người ảo đồng thời trên đường API.')
        if con_lai:
            print(f'     Vỡ từ {con_lai[0]} người trở lên.')
        else:
            print('     Chưa chạm trần — mức cao nhất đã đo vẫn đạt. Đo tiếp mức cao hơn:')
            print(f'       CAC_MUC="{cao} {cao * 2}" ./chay.sh')
    if khong_don_dieu:
        print(f'     ⚠️  Mức {khong_don_dieu} đạt trong khi một mức THẤP hơn trượt.')
        print('        Kết quả không đơn điệu gần như luôn là nhiễu — hàng đợi của mức')
        print('        trước chưa rút cạn, hoặc M1 Max đã throttle rồi hồi lại giữa chừng.')
        print('        Chạy lại riêng hai mức đó trước khi tin con số nào.')
    print(vach)
    print()
    print('  Đường API vỡ  → thiếu CPU cho JVM, hoặc hết Hikari pool '
          f'({nguong["may_chuan"]["hikari_app_pool"]} connection).')
    print(f'  Hàng đợi dài  → 6 judge slot × ~{nguong["may_chuan"]["throughput_cham_uoc_tinh"]}'
          ' bài/s là trần đã biết. Không phải lỗi API, và không sửa được bằng cách sửa API.')
    print()
    return 0


if __name__ == '__main__':
    sys.exit(main())
