import { goi, LoiApi } from './api.js';
import { chu, bao, vaiTroItNhat } from './khung.js';
import { khoiDong } from './trang.js';
import { DUONG } from './duong-dan.js';
import { ganTestdata } from './testdata.js';
import { nhoLai, veGanDay as veDsGanDay } from './de-gan-day.js';
import { ganXoaDe } from './xoa-de.js';

const o = khoiDong({ doiDangNhap: true });

const form = document.getElementById('form-de');
const khuKyThi = document.getElementById('khu-ky-thi');
const nhanKyThi = document.getElementById('nhan-ky-thi');

const kyThiDich = (() => {
    const v = new URLSearchParams(location.search).get('kyThi');
    return v && /^[0-9]+$/.test(v) ? Number(v) : null;
})();
const tieuDeForm = document.getElementById('tieu-de-form');
const nutLuu = document.getElementById('luu');
const nutXuatBan = document.getElementById('xuat-ban');
const nutGoXuong = document.getElementById('go-xuong');
const khuTestdata = document.getElementById('khu-testdata');
const khuTrangThai = document.getElementById('trang-thai-de');
const canhBaoPhanHoi = document.getElementById('canh-bao-phan-hoi');
const ganDay = document.getElementById('gan-day');

let deHienTai = null;

const xoaDe = ganXoaDe({
    o,
    khiXong: () => {
        form.reset();
        datCheDo(null, null, null);
        veGiaiThichPhanHoi();
    },
});

const GIAI_THICH_PHAN_HOI = {
    NONE: 'Kín nhất. Thí sinh chỉ biết đúng hay sai — không rút trích được gì từ verdict.',
    TEST_INDEX: 'Mặc định. Nói sai ở test thứ mấy; đủ để gỡ lỗi, chưa đủ để dựng lại bộ test.',
    SAMPLE_DETAIL: 'Hở nhất. Chỉ hiện chi tiết của test VÍ DỤ — vốn đã công khai — nhưng hãy '
        + 'chắc rằng những test bạn đánh dấu "sample" thật sự là test bạn muốn cho xem.',
};

const NHAN_TRANG_THAI = {
    DRAFT: 'Bản nháp — chưa ai ngoài bạn thấy đề này.',
    PUBLISHED: 'Đã xuất bản — mọi người thấy đề và nộp bài được.',
    RETIRED: 'Đã gỡ xuống — không nhận bài nộp mới. Bài đã nộp giữ nguyên.',
};

function datCheDo(id, code, trangThai) {
    deHienTai = id;
    tieuDeForm.textContent = id ? `Sửa đề #${id}${code ? ` · ${code}` : ''}` : 'Đề mới';
    nutLuu.textContent = id ? 'Lưu thay đổi' : 'Tạo đề';
    khuTestdata.hidden = !id;

    nutXuatBan.hidden = !id || trangThai === 'PUBLISHED';
    nutGoXuong.hidden = !id || trangThai !== 'PUBLISHED';

    khuTrangThai.hidden = !id;
    if (id) {
        khuTrangThai.className = 'thong-bao';
        khuTrangThai.textContent = NHAN_TRANG_THAI[trangThai] || trangThai || '';
    }

    xoaDe.dat(id, code);
}

function doVaoForm(de) {
    form.code.value = de.code || '';
    form.elements.title.value = de.title || '';
    form.statementMd.value = de.statementMd || '';
    form.timeLimitMs.value = de.timeLimitMs;
    form.memoryLimitKb.value = de.memoryLimitKb;
    form.checkerType.value = de.checkerType || 'token';
    form.checkerEpsilon.value = de.checkerEpsilon ?? '';
    form.allowPublicSolutions.checked = Boolean(de.allowPublicSolutions);
    form.scoringMode.value = de.scoringMode || 'ALL_OR_NOTHING';
    form.feedbackLevel.value = de.feedbackLevel || 'TEST_INDEX';
    veGiaiThichPhanHoi();
}

function docTuForm() {
    const eps = form.checkerEpsilon.value.trim();
    return {
        code: form.code.value.trim(),
        title: form.elements.title.value.trim(),
        statementMd: form.statementMd.value,
        timeLimitMs: Number(form.timeLimitMs.value),
        memoryLimitKb: Number(form.memoryLimitKb.value),
        checkerType: form.checkerType.value,
        checkerEpsilon: eps === '' ? null : eps,
        scoringMode: form.scoringMode.value,
        feedbackLevel: form.feedbackLevel.value,
        allowPublicSolutions: form.allowPublicSolutions.checked,
    };
}

function veGiaiThichPhanHoi() {
    canhBaoPhanHoi.textContent = GIAI_THICH_PHAN_HOI[form.feedbackLevel.value] || '';
}

async function moDe(id) {
    bao(o, '');
    try {
        const de = await goi(DUONG.de.soan(id));
        doVaoForm(de);
        datCheDo(id, de.code, de.status);
        nhoLai(id, de.code);
        veDsGanDay(ganDay, moDe);
        ganTestdata(id, { o });
        bao(o, `Đã mở đề #${id}.`, 'on');
    } catch (e) {
        bao(o, e instanceof LoiApi ? e.message : 'Không mở được đề.', 'loi');
    }
}

async function luu(ev) {
    ev.preventDefault();
    nutLuu.disabled = true;
    bao(o, '');
    try {
        const than = docTuForm();
        if (deHienTai) {
            await goi(DUONG.de.sua(deHienTai), { method: 'PUT', body: than });
            nhoLai(deHienTai, than.code);
            veDsGanDay(ganDay, moDe);
            bao(o, 'Đã lưu.', 'on');
        } else if (kyThiDich !== null) {
            const kq = await goi(DUONG.kyThi.soanDe(kyThiDich), {
                method: 'POST',
                body: {
                    de: than,
                    label: form.label.value.trim(),
                    points: Number(form.points.value),
                },
            });
            datCheDo(kq.problemId, than.code, 'DRAFT');
            nhoLai(kq.problemId, than.code);
            veDsGanDay(ganDay, moDe);
            ganTestdata(kq.problemId, { o });
            bao(o, `Đã tạo đề #${kq.problemId} và gắn vào kỳ thi với nhãn `
                + `${form.label.value.trim()}. Nạp bộ test rồi xuất bản thì thí sinh mới `
                + 'mở được.', 'on');
        } else {
            const kq = await goi(DUONG.de.tao, { method: 'POST', body: than });
            datCheDo(kq.problemId, than.code, 'DRAFT');
            nhoLai(kq.problemId, than.code);
            veDsGanDay(ganDay, moDe);
            ganTestdata(kq.problemId, { o });
            bao(o, `Đã tạo đề #${kq.problemId}. Ghi lại số này — máy chủ không có danh sách `
                + 'bản nháp để tra lại.', 'on');
        }
    } catch (e) {
        bao(o, e instanceof LoiApi ? e.message : 'Không lưu được đề.', 'loi');
    } finally {
        nutLuu.disabled = false;
    }
}

async function doiTrangThai(duongDan, hoi, xong) {
    if (!confirm(hoi)) return;
    bao(o, '');
    try {
        await goi(duongDan, { method: 'POST' });
        bao(o, xong, 'on');
    } catch (e) {
        bao(o, e instanceof LoiApi ? e.message : 'Không đổi được trạng thái đề.', 'loi');
    }
}

function batCheDoKyThi() {
    if (kyThiDich === null) return;
    khuKyThi.hidden = false;
    form.label.required = true;
    nhanKyThi.textContent = `Đề này sẽ được soạn RIÊNG cho kỳ thi #${kyThiDich}. `
        + 'Nó không xuất hiện ở trang Đề bài cho tới khi kỳ thi kết thúc.';
    document.getElementById('tieu-de-form').textContent = 'Đề mới cho kỳ thi';
}

async function moTheoDuongDan() {
    const q = new URLSearchParams(location.search);
    const id = q.get('id');
    if (id && /^[0-9]+$/.test(id)) {
        await moDe(Number(id));
        return;
    }
    const ma = q.get('ma');
    if (!ma) return;
    try {
        const de = await goi(DUONG.de.theoMa(ma));
        await moDe(de.problemId);
    } catch (e) {
        bao(o, e instanceof LoiApi ? e.message
            : `Không mở được đề có mã \u201c${ma}\u201d.`, 'loi');
    }
}

if (o) {
    if (!vaiTroItNhat('SETTER')) {
        bao(o, 'Tài khoản của bạn không có quyền soạn đề. Máy chủ sẽ từ chối mọi thao tác ở '
            + 'trang này.', 'loi');
    }

    veDsGanDay(ganDay, moDe);
    veGiaiThichPhanHoi();
    datCheDo(null, null, null);
    batCheDoKyThi();
    moTheoDuongDan();

    form.addEventListener('submit', luu);
    form.feedbackLevel.addEventListener('change', veGiaiThichPhanHoi);

    document.getElementById('form-mo').addEventListener('submit', (ev) => {
        ev.preventDefault();
        moDe(Number(ev.target.problemId.value));
    });

    document.getElementById('de-moi').addEventListener('click', () => {
        form.reset();
        datCheDo(null, null, null);
        veGiaiThichPhanHoi();
        bao(o, 'Đang soạn một đề mới.', 'on');
    });

    nutXuatBan.addEventListener('click', () => doiTrangThai(
        DUONG.de.xuatBan(deHienTai),
        'Xuất bản đề này? Sau đó mọi người thấy đề và nộp bài được.',
        'Đã xuất bản.'));

    nutGoXuong.addEventListener('click', () => doiTrangThai(
        DUONG.de.goXuong(deHienTai),
        'Gỡ đề xuống? Đề sẽ không nhận bài nộp mới nữa. Bài đã nộp giữ nguyên.',
        'Đã gỡ đề xuống.'));
}
