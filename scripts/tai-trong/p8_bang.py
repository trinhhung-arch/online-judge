"""P8 đo trên màn hình người xem — bao-cao-db.py dùng cho kịch bản `ky-thi`.

★ ĐỊNH NGHĨA. Tài khoản tai-k có bài AC đầu tiên trong kỳ thi lúc J (judged_at). T là giờ sse-tai.py nhận
khung đầu tiên có cuaToi.soBaiDat ≥ 1 trên kết nối của tai-k. lag = T − J: từ lúc có verdict tới lúc
bài hiện lên bảng CỦA CHÍNH NGƯỜI NỘP — gồm nhịp lô 2s, đọc lại bảng, và cả đường mạng.

★ ĐỒNG HỒ. judged_at do Clock của API ghi (JdbcSubmissionRepository), T do time.time() của máy chạy
sse-tai.py: cùng một đồng hồ khi cả hai ở một máy. Chạy client từ máy khác thì phải đồng bộ NTP — lag
ÂM là dấu hiệu lệch, được đếm riêng chứ không trộn vào phân vị.

★ BA KẾT CỤC CHO MỖI BÀI AC, không bài nào bị lặng lẽ bỏ qua:
  đo được        — kết nối mở trước J và đã thấy.
  thiếu cập nhật — kết nối mở trước J, còn mở tới J + CHO_S mà vẫn chưa thấy: HỎNG (FR-CON-04).
  không theo dõi — kết nối không mở được, mở sau J, hoặc đóng trước J + CHO_S: không kết luận được.
"""
import io
import json
import math

CHO_S = 15   # nhịp lô 2s + đọc lại bảng + mạng, dư nhiều lần


def phan_vi(xs, q):
    return sorted(xs)[math.ceil(q * len(xs)) - 1] if xs else None


def tinh(duong_dan, bai_ac):
    """bai_ac: [(handle, judged_epoch)] → (metrics, số lag âm)."""
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
        # "0 người thiếu trên 0 người theo dõi được" không phải bằng chứng — cùng luật với db_bai_mat.
        'sse_thieu_cap_nhat': thieu if co_ket_luan else None,
        'sse_khong_theo_doi': khong if bai_ac else None,
    }, am
