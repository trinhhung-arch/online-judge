/**
 * Biến bảng số của k6 thành một câu trả lời: "ở mức này, ĐẠT hay KHÔNG ĐẠT".
 *
 * Tách khỏi `k6-tai.js` vì hai lý do, không phải vì thẩm mỹ:
 *   · `k6-tai.js` là kịch bản tải — nó phải đọc được như mô tả hành vi người dùng.
 *     Trộn 130 dòng định dạng bảng vào đó là làm mờ đúng phần cần soát kỹ nhất.
 *   · Ngưỡng nằm ở `nguong.json`, và file này chỉ áp dụng chứ không tự đặt ra
 *     con số nào. Grep `200` trong file này sẽ không ra gì — cố ý.
 *
 * ★ VÌ SAO `handleSummary` TỰ VẼ BẢNG THAY VÌ DÙNG BẢNG MẶC ĐỊNH CỦA k6
 * Bảng mặc định của k6 liệt kê ~25 metric ngang hàng nhau, trong đó `http_req_tls_handshaking`
 * và `doc_ms` to bằng nhau. Người đọc phải tự biết metric nào là SLO. Bảng dưới đây xếp
 * chúng theo *câu hỏi*: nhóm quyết định đạt/không đạt ở trên, nhóm chỉ để đọc đường cong ở
 * dưới. Cái giá là mất bảng gốc — nên `chay.sh` vẫn ghi JSON đầy đủ ra đĩa.
 */

/** k6 chạy trên goja; `Intl` không có. Tách nghìn bằng tay. */
function so(n, chuSoThapPhan) {
    if (n === null || n === undefined || Number.isNaN(n)) return '—';
    const s = Number(n).toFixed(chuSoThapPhan === undefined ? 0 : chuSoThapPhan);
    const [nguyen, le] = s.split('.');
    const co = nguyen.replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
    return le ? `${co}.${le}` : co;
}

function dinhDang(giaTri, donVi) {
    if (giaTri === null || giaTri === undefined) return '—';
    if (donVi === 'ti_le') return `${so(giaTri * 100, 2)} %`;
    if (donVi === 'ms') return `${so(giaTri)} ms`;
    return so(giaTri);
}

function dem(s, n) {
    let r = String(s);
    while (r.length < n) r += ' ';
    return r;
}

function demTrai(s, n) {
    let r = String(s);
    while (r.length < n) r = ' ' + r;
    return r;
}

/**
 * Lấy một con số ra khỏi `data.metrics` của k6.
 *
 * Trả `null` khi metric không tồn tại chứ KHÔNG trả 0: hai chuyện đó khác nhau hoàn toàn.
 * `dropped_iterations` vắng mặt nghĩa là không có vòng nào bị bỏ (tốt); `doc_ms` vắng mặt
 * nghĩa là không ai đọc được đề nào trong cả lượt chạy (rất tệ). Trả 0 cho cả hai là xoá
 * mất sự khác biệt ấy — nên chỗ nào coi "vắng mặt = 0" thì phải khai `mac_dinh` trong
 * `nguong.json`, thành một quyết định viết ra chứ không phải một mặc định lặng lẽ.
 */
export function docSo(metrics, ten, thongKe, macDinh) {
    const m = metrics[ten];
    if (!m || !m.values) return macDinh === undefined ? null : macDinh;
    const v = m.values[thongKe];
    if (v === undefined || v === null) return macDinh === undefined ? null : macDinh;
    return v;
}

/** Áp `nguong.cung` — nhóm duy nhất quyết định đạt/không đạt. */
export function chamCung(metrics, nguong) {
    return nguong.cung.map((d) => {
        const giaTri = docSo(metrics, d.metric, d.thong_ke, d.mac_dinh);
        return {
            ma: d.ma,
            nhan: d.nhan,
            donVi: d.don_vi,
            chuThich: d.chu_thich,
            giaTri,
            toiDa: d.toi_da,
            // Không đo được thì KHÔNG coi là đạt. Một ngưỡng vắng mặt lặng lẽ
            // trở thành ngưỡng xanh là cách nhanh nhất để một bộ đo mất giá trị.
            dat: giaTri === null ? false : giaTri <= d.toi_da,
            doDuoc: giaTri !== null,
        };
    });
}

/** Áp `nguong.quan_sat` — chỉ đọc, không chấm. */
export function chamQuanSat(metrics, nguong) {
    return nguong.quan_sat.map((d) => ({
        ma: d.ma,
        nhan: d.nhan,
        donVi: d.don_vi,
        chuThich: d.chu_thich,
        moc: d.moc,
        giaTri: docSo(metrics, d.metric, d.thong_ke, d.mac_dinh),
    }));
}

function veHang(r) {
    const dau = r.dat ? '✅' : (r.doDuoc ? '❌' : '⚠️ ');
    const nguong = r.toiDa === 0 ? '= 0' : `< ${dinhDang(r.toiDa, r.donVi)}`;
    return `  ${dem(r.ma, 5)} ${dem(r.nhan, 30)} ${demTrai(dinhDang(r.giaTri, r.donVi), 12)}`
        + `   ${dem(nguong, 12)} ${dau}`;
}

function veHangQuanSat(r) {
    const moc = r.moc === undefined ? '' : `   mốc ${dinhDang(r.moc, r.donVi)}`;
    return `  ${dem(r.ma, 9)} ${dem(r.nhan, 30)} ${demTrai(dinhDang(r.giaTri, r.donVi), 12)}${moc}`;
}

/** Chú thích dài phải xuống dòng, nếu không nó đẩy bảng ra khỏi bề ngang terminal. */
function xuongDong(chu, le, rong) {
    const ra = [];
    let dong = '';
    chu.split(' ').forEach((tu) => {
        if (dong && (dong + ' ' + tu).length > rong) { ra.push(le + dong); dong = tu; }
        else { dong = dong ? dong + ' ' + tu : tu; }
    });
    if (dong) ra.push(le + dong);
    return ra;
}

/**
 * @param boiCanh {{nguoi, thoiLuong, base, tiLeNop, docLen}}
 */
function ve(metrics, nguong, boiCanh) {
    const cung = chamCung(metrics, nguong);
    const quanSat = chamQuanSat(metrics, nguong);
    const dat = cung.every((r) => r.dat);
    const d = [];
    const vach = '═'.repeat(78);

    d.push('');
    d.push(vach);
    d.push(`  ${boiCanh.nguoi} NGƯỜI ẢO · ${boiCanh.thoiLuong} · ${boiCanh.base}`);
    d.push(`  dốc lên ${boiCanh.docLen} (không tính vào p95) · ${Math.round(boiCanh.tiLeNop * 100)}% lượt là nộp bài`);
    d.push(vach);
    d.push('');
    d.push('ĐƯỜNG API — đây là câu trả lời cho "chịu được bao nhiêu người"');
    d.push('');
    cung.forEach((r) => d.push(veHang(r)));
    d.push('');
    d.push(dat
        ? `  ➜  ĐẠT ở ${boiCanh.nguoi} người ảo.`
        : `  ➜  KHÔNG ĐẠT ở ${boiCanh.nguoi} người ảo — xem dòng ❌ ở trên.`);
    cung.filter((r) => !r.doDuoc).forEach((r) => {
        d.push(`     ⚠️  "${r.nhan}" không đo được. Không có số thì không kết luận được — coi như trượt.`);
    });
    d.push('');
    d.push('ĐƯỜNG CHẤM — KHÔNG phải tiêu chí đạt/không đạt, đọc đường cong');
    d.push('');
    quanSat.forEach((r) => {
        d.push(veHangQuanSat(r));
        if (r.chuThich) xuongDong(r.chuThich, '            ', 64).forEach((l) => d.push(l));
    });
    d.push('');
    d.push('LƯU LƯỢNG');
    d.push('');
    nguong.luu_luong.forEach((l) => {
        d.push(`  ${dem(l.nhan, 40)} ${demTrai(dinhDang(docSo(metrics, l.metric, l.thong_ke), l.don_vi), 12)}`);
    });
    d.push(`  ${dem('VU cao nhất', 40)} ${demTrai(so(docSo(metrics, 'vus_max', 'max')), 12)}`);
    d.push('');

    if (/localhost|127\.0\.0\.1|\[::1\]/.test(boiCanh.base)) {
        d.push('  ⚠️  k6 đang chạy trên CHÍNH máy được đo (BASE trỏ localhost).');
        d.push('      nfrplan 2.2 chỉ chừa 3 core cho macOS + Postgres + Redis + RabbitMQ + JVM.');
        d.push('      Từ ~400 VU, k6 ăn thêm 1–2 core của đúng 3 core ấy, nên con số đọc được');
        d.push('      là con số của một máy chủ nhỏ hơn máy chủ thật. Muốn số dùng được ở 400+,');
        d.push('      chạy k6 từ máy khác qua LAN.');
        d.push('');
    }
    return d.map((l) => l.replace(/\s+$/, '')).join('\n');
}

/**
 * Dựng giá trị trả về cho `handleSummary` của k6: bảng kết luận ra stdout, `data` nguyên vẹn
 * ra file nếu `boiCanh.raJson` có đường dẫn.
 *
 * ★ VÌ SAO VẪN GHI `data` NGUYÊN VẸN dù đã có bảng riêng: bảng ở trên là ý kiến — nó chọn
 * ~15 con số trong ~25 và xếp chúng theo một câu hỏi. Ba tháng nữa câu hỏi có thể khác, và
 * lúc đó ta cần số gốc chứ không cần ý kiến cũ. File JSON là số gốc; `tong-hop.py` đọc chính
 * nó chứ không parse lại stdout.
 */
export function tomTat(data, nguong, boiCanh) {
    const ra = { stdout: ve(data.metrics, nguong, boiCanh) + '\n' };
    if (boiCanh.raJson) {
        ra[boiCanh.raJson] = JSON.stringify({
            nguoi: boiCanh.nguoi,
            thoi_luong: boiCanh.thoiLuong,
            doc_len: boiCanh.docLen,
            base: boiCanh.base,
            ti_le_nop: boiCanh.tiLeNop,
            dat: chamCung(data.metrics, nguong).every((r) => r.dat),
            metrics: data.metrics,
        }, null, 2);
    }
    return ra;
}
