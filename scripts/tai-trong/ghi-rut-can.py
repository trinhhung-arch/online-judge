"""Vá số liệu pha RÚT CẠN vào `tom-tat-<mức>.json` mà k6 vừa ghi.

    ghi-rut-can.py <file.json> <da_rut> <trong_giay> <xong:0|1>

★ VÌ SAO PHA NÀY PHẢI CÓ TRONG BẢNG

`verdict_ms` của k6 bị cắt ở trần chờ (mặc định 60s) và luồng `giam_sat` chết cùng
lúc với người ảo — nên cả P3 lẫn P6 đều ngừng đo ĐÚNG LÚC tệ nhất, là lúc tải đã
dừng mà hàng đợi còn hàng nghìn bài. Tốc độ rút cạn không dính cả hai lỗi ấy: nó đo
trên hàng đợi tồn, không lẫn thời gian xếp hàng, và chay.sh đo nó bằng đồng hồ thật.

Đo ngày 2026-09-05: 346–350 bài/phút suốt 12 lần lấy mẫu liên tiếp ở mức 400 người.
Độ ổn định ấy là thứ P3 không bao giờ có được dưới tải.

★ VÌ SAO GHI VÀO JSON CHỨ KHÔNG IN RA

`tong-hop.py` đọc JSON chứ không parse stdout, và `nguong.json` là nơi duy nhất khai
báo hiển thị. Nhét hai con số vào `metrics` là chúng đi qua đúng con đường của mọi
metric khác — không có nhánh riêng nào phải bảo trì.
"""
import io
import json
import sys


def main():
    if len(sys.argv) != 5:
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    duong_dan, da_rut, trong, xong = sys.argv[1], float(sys.argv[2]), float(sys.argv[3]), sys.argv[4]

    with io.open(duong_dan, encoding='utf-8') as fh:
        d = json.load(fh)
    m = d.setdefault('metrics', {})

    # Tốc độ tính được ở CẢ HAI nhánh — rút cạn xong hay hết giờ đều cho một tốc độ đúng.
    if trong > 0 and da_rut > 0:
        m['rut_can_bai_phut'] = {'values': {'value': da_rut * 60.0 / trong}}

    # "Thời gian rút cạn" chỉ có nghĩa khi hàng đợi THẬT SỰ về 0. Hết giờ mà còn tồn thì
    # metric này vắng mặt — và vắng mặt in ra "—", chứ không phải một con số trông như đo được.
    if xong == '1':
        m['rut_can_giay'] = {'values': {'value': trong}}

    with io.open(duong_dan, 'w', encoding='utf-8') as fh:
        json.dump(d, fh, ensure_ascii=False, indent=2)
    return 0


if __name__ == '__main__':
    sys.exit(main())
