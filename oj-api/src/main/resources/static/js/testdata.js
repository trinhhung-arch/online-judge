import { goi, LoiApi, accessToken } from './api.js';
import { bao } from './khung.js';
import { DUONG } from './duong-dan.js';
import { theoDoi } from './tien-do-job.js';

const form = document.getElementById('form-testdata');
const nutNap = document.getElementById('nap');
const nutTaiVe = document.getElementById('tai-ve');
const oTienDo = document.getElementById('tien-do');
const oTep = document.getElementById('tep');

let deHienTai = null;
let dangTheoDoi = null;
let daNoiDay = false;

function tenTuHeader(header, macDinh) {
    const m = /filename="?([^"]+)"?/.exec(header || '');
    return m ? m[1] : macDinh;
}

async function nap(ev) {
    ev.preventDefault();
    if (!deHienTai) return;

    const tep = oTep.files?.[0];
    if (!tep) {
        bao(o(), 'Chưa chọn gói testdata.', 'loi');
        return;
    }

    nutNap.disabled = true;
    bao(o(), '');
    try {
        const than = new FormData();
        than.append('file', tep);
        const kq = await goi(DUONG.de.testdata(deHienTai), { method: 'POST', body: than });

        bao(o(), `Đã nhận gói. Việc #${kq.jobId} đang chạy ở máy chủ — rời trang cũng không `
            + 'dừng nó.', 'on');

        dangTheoDoi?.dung();
        dangTheoDoi = theoDoi(kq.jobId, {
            vao: oTienDo,
            khiXong: (job) => {
                if (job.status === 'DONE') {
                    bao(o(), 'Nạp bộ test xong.', 'on');
                    form.reset();
                } else {
                    bao(o(), `Việc #${job.id} kết thúc với trạng thái ${job.status}.`, 'loi');
                }
            },
        });
    } catch (e) {
        bao(o(), e instanceof LoiApi ? e.message : 'Không nạp được gói testdata.', 'loi');
    } finally {
        nutNap.disabled = false;
    }
}

async function taiVe() {
    if (!deHienTai) return;
    nutTaiVe.disabled = true;
    bao(o(), '');
    try {
        const res = await fetch(DUONG.de.testdata(deHienTai), {
            headers: accessToken() ? { Authorization: `Bearer ${accessToken()}` } : {},
        });
        if (!res.ok) {
            let thongDiep = 'Không tải được bộ test.';
            try {
                thongDiep = (await res.json())?.message || thongDiep;
            } catch {
            }
            bao(o(), thongDiep, 'loi');
            return;
        }

        const blob = await res.blob();
        const ten = tenTuHeader(res.headers.get('Content-Disposition'),
            `testdata-${deHienTai}.zip`);
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = ten;
        document.body.append(a);
        a.click();
        a.remove();
        setTimeout(() => URL.revokeObjectURL(url), 60_000);
        bao(o(), `Đang tải ${ten}.`, 'on');
    } catch {
        bao(o(), 'Không tải được bộ test. Kiểm tra kết nối rồi thử lại.', 'loi');
    } finally {
        nutTaiVe.disabled = false;
    }
}

let vungBao = null;
function o() {
    return vungBao;
}

export function ganTestdata(problemId, { o: vung }) {
    deHienTai = problemId;
    vungBao = vung;

    dangTheoDoi?.dung();
    dangTheoDoi = null;
    oTienDo.replaceChildren();

    if (daNoiDay) return;
    daNoiDay = true;

    form.addEventListener('submit', nap);
    nutTaiVe.addEventListener('click', taiVe);
    window.addEventListener('pagehide', () => dangTheoDoi?.dung());
}
