/**
 * Load test — trả lời câu hỏi "hệ thống chịu được bao nhiêu người".
 *
 * ════════════════════════════════════════════════════════════════════════════
 * ★ ĐỌC BA ĐIỀU NÀY TRƯỚC KHI CHẠY
 * ════════════════════════════════════════════════════════════════════════════
 *
 * 1. NÓ GHI DỮ LIỆU THẬT. Mỗi lần chạy tạo hàng nghìn dòng `submissions` và
 *    làm bẩn `contest_standings`. ĐỪNG trỏ vào database bạn đang dùng để phát
 *    triển, và càng đừng trỏ vào production. Dọn bằng `don-dep.sql`.
 *
 * 2. RATE LIMIT LÀ TRẦN THẬT, KHÔNG PHẢI NHIỄU. FR-SUB-08 cho 1 bài / 10 giây
 *    / người. Nên N người ảo chỉ chào được N/10 bài mỗi giây, bất kể bạn đặt
 *    `rps` bao nhiêu. 1000 người ⇒ tối đa 100 bài/s. Năng lực chấm là ~5 bài/s
 *    (6 slot, nfrplan 2.2). Nghĩa là ngay ở 100 người ảo, hàng đợi ĐÃ quá tải
 *    gấp đôi — và đó là kết quả, không phải lỗi cấu hình.
 *
 * 3. PHÉP ĐO KHÔNG ĐƯỢC TRỞ THÀNH TẢI. Nếu cả 1000 người ảo cùng hỏi
 *    `GET /submissions/{id}` để chờ verdict thì riêng việc đo đã là 2000 req/s
 *    và con số đo được là con số của một hệ thống khác. Vì thế:
 *      · chỉ `TI_LE_THEO_DOI` (mặc định 10%) số người ảo theo bài của mình,
 *      · độ sâu hàng đợi đo bằng ĐÚNG MỘT người ảo hỏi `GET /api/v1/status`
 *        mỗi giây — endpoint ấy đọc mẫu đã lấy sẵn, không quét bảng.
 *
 * ════════════════════════════════════════════════════════════════════════════
 * CÁCH CHẠY
 * ════════════════════════════════════════════════════════════════════════════
 *
 *   # 1. seed tài khoản (một lần, cho số người lớn nhất bạn định chạy)
 *   psql "$OJ_DB_URL" -v so_nguoi=1000 -f seed-nguoi-dung.sql
 *
 *   # 2. đo một mức
 *   k6 run -e NGUOI=100 k6-tai.js
 *
 *   # hoặc cả năm mức 100 → 200 → 400 → 500 → 1000, có nghỉ giữa các mức để
 *   # hàng đợi rút cạn và để máy nguội (xem ghi chú nhiệt trong chay.sh):
 *   ./chay.sh
 *
 * Biến môi trường: BASE · NGUOI · DE_ID · NGON_NGU · TI_LE_NOP · TI_LE_THEO_DOI
 *                  THOI_LUONG · DOC_LEN · MAT_KHAU · RA_JSON
 *
 * ════════════════════════════════════════════════════════════════════════════
 * ĐỌC KẾT QUẢ
 * ════════════════════════════════════════════════════════════════════════════
 *
 *   doc_ms          p95 < 200ms   → SLO P1
 *   nop_ms          p95 < 300ms   → SLO P2   (chỉ tính lần 202, không tính 429)
 *   dang_nhap_ms    —             bcrypt cost 12, ~250ms là ĐÚNG chứ không chậm
 *   verdict_ms      p95 < 2000ms  → SLO P3   (chỉ đúng khi hàng đợi rỗng)
 *   hang_doi        —             số bài đang chờ; nó TĂNG TUYẾN TÍNH là đã vỡ
 *   cho_lau_nhat_ms p95 < 5000ms  → SLO P6
 *   ti_le_429       —             tỉ lệ chạm rate limit; cao là bình thường
 *
 * Ngưỡng `thresholds` dưới đây cố ý KHÔNG đặt cho `hang_doi`: một hàng đợi dài
 * không phải lỗi, nó là câu trả lời. Đọc đường cong, đừng đọc pass/fail.
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';
import exec from 'k6/execution';
import { tomTat } from './ket-luan.js';

// Ngưỡng ở một chỗ duy nhất, dùng chung với tong-hop.py. `open()` chỉ gọi được
// trong init context — đó là lý do dòng này nằm ở đây chứ không trong hàm.
const NGUONG = JSON.parse(open('./nguong.json'));

// ★ 429 KHÔNG phải request hỏng — nó là rate limit đang làm đúng việc của nó
// (FR-SUB-08). Mặc định k6 coi mọi mã ≥ 400 là hỏng, nên không khai báo dòng
// này thì ngưỡng `http_req_failed` sẽ đỏ ở mọi lượt chạy đông người, và nó đỏ
// vì hệ thống hoạt động ĐÚNG. Một ngưỡng như thế dạy người ta bỏ qua màu đỏ.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 429));

const BASE = __ENV.BASE || 'http://localhost:8080';
const NGUOI = Number(__ENV.NGUOI || 100);
const DE_ID = Number(__ENV.DE_ID || 1);
const NGON_NGU = __ENV.NGON_NGU || 'cpp20';
const MAT_KHAU = __ENV.MAT_KHAU || 'matkhau-dev-123';
const TI_LE_NOP = Number(__ENV.TI_LE_NOP || 0.3);
const TI_LE_THEO_DOI = Number(__ENV.TI_LE_THEO_DOI || 0.1);
const THOI_LUONG = __ENV.THOI_LUONG || '3m';

/**
 * ★ TRẦN CHỜ VERDICT — ĐÂY LÀ TRẦN CỦA PHÉP ĐO, KHÔNG PHẢI CỦA HỆ THỐNG.
 *
 * Người ảo chờ tối đa ngần này rồi thôi. Nâng nó lên thì đo được cái đuôi dài hơn, nhưng
 * cũng giữ người ảo đứng im lâu hơn — mà một người ảo đang chờ thì không sinh tải, nên
 * nâng quá tay là tự giảm tải của chính phép đo. 60s là chỗ đứng giữa.
 *
 * Xem `theoToiVerdict`: mẫu chạm trần được GHI LẠI ở đúng giá trị trần chứ không bị vứt.
 */
const HAN_VERDICT_MS = Number(__ENV.HAN_VERDICT_S || 60) * 1000;

/**
 * ★ ĐOẠN DỐC LÊN PHẢI DÀI RA THEO SỐ NGƯỜI, VÀ LÝ DO LÀ bcrypt.
 *
 * `bcrypt-cost: 12` tốn ~250ms CPU mỗi lần băm (application.yml, FR-AUTH-01). Mỗi người ảo
 * đăng nhập đúng một lần, ở vòng lặp đầu của nó. Dốc 1000 người trong 30 giây nghĩa là ép
 * máy chủ làm 1000 × 250ms = 250 giây CPU trong 30 giây — cần 8.3 core, tức là NHIỀU HƠN
 * toàn bộ 8 P-core của M1 Max. Máy sẽ nghẽn ở đoạn dốc, và cái nghẽn đó không nói gì về
 * đường đọc/nộp mà ta định đo; nó chỉ nói rằng ta đã tự dựng sân sai.
 *
 * Nên: ~10 người mỗi giây, tức là ~2.5 core cho bcrypt bất kể mức nào. 1000 người ⇒ dốc 100s.
 */
const DOC_LEN = __ENV.DOC_LEN || `${Math.max(30, Math.ceil(NGUOI / 10))}s`;

/**
 * Người ảo giám sát phải sống ĐÚNG BẰNG cả lượt chạy, nếu không đường cong hàng
 * đợi sẽ cụt ở đúng đoạn thú vị nhất — lúc tải đã lên đỉnh. k6 đòi mỗi scenario
 * một khoảng thời gian cố định, nên phải tự cộng: DOC_LEN + THOI_LUONG +
 * 15s dốc xuống.
 */
function giay(d) {
    const m = /^(\d+(?:\.\d+)?)(ms|s|m|h)$/.exec(String(d).trim());
    if (!m) throw new Error(`THOI_LUONG không đọc được: ${d} (ví dụ hợp lệ: 3m, 180s)`);
    const he = { ms: 0.001, s: 1, m: 60, h: 3600 }[m[2]];
    return Number(m[1]) * he;
}
const DOC_LEN_MS = giay(DOC_LEN) * 1000;
const TONG_GIAY = giay(DOC_LEN) + giay(THOI_LUONG) + 15;

/**
 * ★ p95 CHỈ ĐƯỢC TÍNH TỪ LÚC ĐÃ ĐỦ NGƯỜI.
 *
 * Trong đoạn dốc lên, số người ảo đi từ 0 tới NGUOI. Những request ở giây thứ 5 gặp một máy
 * chủ gần như rỗng và trả về rất nhanh. Gộp chúng vào p95 là pha loãng — và pha loãng theo
 * hướng LÀM ĐẸP: mức càng đông thì đoạn dốc càng dài, càng nhiều số đẹp được cộng vào. Một
 * phép đo tự thưởng cho mình khi tải tăng thì không dùng được để trả lời câu hỏi nào cả.
 *
 * `http_req_failed` thì KHÔNG lọc: một request hỏng lúc đang dốc lên vẫn là một request hỏng.
 */
function dangDo() {
    return exec.instance.currentTestRunDuration >= DOC_LEN_MS;
}
const docMs = new Trend('doc_ms', true);
const nopMs = new Trend('nop_ms', true);
const dangNhapMs = new Trend('dang_nhap_ms', true);
const verdictMs = new Trend('verdict_ms', true);
const hangDoi = new Trend('hang_doi');
const choLauNhat = new Trend('cho_lau_nhat_ms', true);
const mayChamSong = new Trend('may_cham_song');
const ti429 = new Rate('ti_le_429');
const boCuoc = new Counter('verdict_khong_kip');
/**
 * Số mẫu `verdict_ms` chạm trần, tức là bị KIỂM DUYỆT (censored): giá trị thật của chúng
 * lớn hơn cái được ghi, không ai biết lớn bao nhiêu. Lọc bằng `dangDo()` y như `verdict_ms`
 * để `verdict_cham_tran / verdict_ms.count` là tỉ lệ đúng, không phải hai mẫu số khác nhau.
 */
const chamTran = new Counter('verdict_cham_tran');

export const options = {
    discardResponseBodies: false,
    scenarios: {
        nguoi_dung: {
            executor: 'ramping-vus',
            exec: 'nguoiDung',
            startVUs: 0,
            stages: [
                { duration: DOC_LEN, target: NGUOI },  // dốc lên, không dựng đứng
                { duration: THOI_LUONG, target: NGUOI },
                { duration: '15s', target: 0 },
            ],
            gracefulRampDown: '30s',
        },
        // Một người ảo duy nhất, và nó là dụng cụ đo chứ không phải tải.
        giam_sat: {
            executor: 'constant-vus',
            exec: 'giamSat',
            vus: 1,
            duration: `${TONG_GIAY}s`,
            startTime: '0s',
        },
    },
    thresholds: {
        'doc_ms': ['p(95)<200'],
        'nop_ms': ['p(95)<300'],
        'http_req_failed{scenario:nguoi_dung}': ['rate<0.01'],
    },
    // 'count' là BẮT BUỘC, không phải trang trí: `ket-luan.js` chia
    // verdict_cham_tran / verdict_ms.count để ra tỉ lệ mẫu bị kiểm duyệt. Bỏ nó đi thì
    // mẫu số bằng 0, tỉ lệ bằng 0, và cảnh báo "P3 không dùng được" im lặng biến mất —
    // đúng cái im lặng mà nó sinh ra để phá.
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'],
};

/**
 * Bài A+B. Dòng đầu `// EXPECT: AC` là một chỉ thị CỐ Ý, phục vụ CẢ HAI runner của worker:
 * với `IsolateJudgeRunner` (M2, thật) nó chỉ là comment C++ vô hại và bài vẫn AC vì cộng
 * đúng; với `ScriptedJudgeRunner` (M1, giả lập — được cắm khi `sandbox.enabled=false`, bắt
 * buộc trên macOS vì isolate cần cgroup v2 của Linux) thì nó là thứ duy nhất được đọc.
 *
 * Thiếu dòng này, mọi bài nhận `IE` tức thì. Đo thật ngày 2026-09-05 trên host M1 Max:
 * verdict p95 = 252ms ở cả bốn mức 100–500 (đúng bằng sàn đo 250ms), hàng đợi không bao giờ
 * quá 3, và mỗi bài còn bị chấm lại 2 lần nữa vì FR-SUB-12 coi IE là đáng thử lại — gấp ba
 * tải hàng đợi một cách vô ích. Cả cột "đường chấm" khi ấy đo tốc độ của việc không làm gì.
 */
const NGUON_GOC = `// EXPECT: AC
#include <bits/stdc++.h>
int main(){long long a,b;if(!(std::cin>>a>>b))return 0;std::cout<<a+b<<"\\n";}
`;

/**
 * ★ VÌ SAO MỖI BÀI NỘP PHẢI MANG MỘT SOURCE KHÁC NHAU
 *
 * Worker có `CompileCache`, khoá là `sha256(source + ngôn ngữ + lệnh biên dịch)`
 * (`nfrplan.md` 2.3 mục 3). Nếu cả lượt chạy dùng CHUNG một chuỗi source thì đúng MỘT bài
 * phải biên dịch, còn lại là cache hit — và cả cột "đường chấm" đo tốc độ của cache chứ
 * không phải của máy chấm. Tệ hơn: cache nằm ở `/var/tmp/oj-worker` trong container, nên
 * nó sống qua nhiều lượt chạy, và bài đầu tiên cũng hết phải biên dịch từ lượt thứ hai.
 *
 * Đo thật ngày 2026-09-05 (100 VU, worker chạy IsolateJudgeRunner thật, host_factor 1.000):
 * verdict p95 = 350ms · `submissions.time_ms` = 1–2ms · judged_at − created_at = 30–520ms.
 * Sàn 500ms của `ket-luan.js` báo động "worker đang giả lập" trong khi worker đang chấm
 * bằng isolate thật — cảnh báo sai, và nó SẼ luôn sai chừng nào source còn trùng nhau.
 *
 * Không kỳ thi thật nào có hàng nghìn bài nộp trùng source, nên mặc định là DUY NHẤT: đó
 * mới là workload phải dùng để trả lời "chịu được bao nhiêu người". Đặt `NGUON_DUY_NHAT=0`
 * nếu muốn cố ý đo trường hợp cache đầy — biết mình đang đo cái gì thì đo cái đó được.
 *
 * Token nằm ở CUỐI file, không phải đầu: `// EXPECT: AC` phải giữ nguyên là dòng đầu tiên
 * cho `ScriptedJudgeRunner`, và một comment thừa ở cuối thì C++ bỏ qua.
 */
const NGUON_DUY_NHAT = (__ENV.NGUON_DUY_NHAT || '1') !== '0';

function nguon() {
    if (!NGUON_DUY_NHAT) {
        return NGUON_GOC;
    }
    return `${NGUON_GOC}// duy-nhat ${__VU}-${__ITER}-${Date.now()}-${Math.random()}\n`;
}

/** Token của từng người ảo, giữ qua các vòng lặp. `__VU` bắt đầu từ 1. */
const phien = {};

function dangNhap() {
    const handle = `tai-${((__VU - 1) % NGUOI) + 1}`;
    const res = http.post(`${BASE}/api/v1/auth/login`,
        JSON.stringify({ dinhDanh: handle, password: MAT_KHAU }),
        { headers: { 'Content-Type': 'application/json' }, tags: { viec: 'dang-nhap' } });

    dangNhapMs.add(res.timings.duration);
    if (res.status !== 200) {
        // Không có tài khoản ⇒ chưa chạy seed-nguoi-dung.sql. Nói ra một lần,
        // đừng để cả lượt chạy ra một bảng số 0 mà không ai hiểu vì sao.
        console.error(`Đăng nhập ${handle} thất bại (${res.status}). `
            + 'Đã chạy seed-nguoi-dung.sql với đủ so_nguoi chưa?');
        return null;
    }
    return res.json('accessToken');
}

function tieuDe(token) {
    return {
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    };
}

/** Đọc: hai trang mà người thật mở nhiều nhất. */
function doc(token) {
    const res = http.get(`${BASE}/api/v1/problems?size=20`,
        { ...tieuDe(token), tags: { viec: 'doc' } });
    if (dangDo()) docMs.add(res.timings.duration);
    check(res, { 'đọc danh sách đề 200': (r) => r.status === 200 });
}

function nop(token) {
    const res = http.post(`${BASE}/api/v1/submissions`,
        JSON.stringify({ problemId: DE_ID, languageCode: NGON_NGU, source: nguon() }),
        { ...tieuDe(token), tags: { viec: 'nop' } });

    ti429.add(res.status === 429);
    if (res.status === 429) return;              // chạm rate limit: đúng như thiết kế

    if (dangDo()) nopMs.add(res.timings.duration);
    const ok = check(res, { 'nộp bài 202': (r) => r.status === 202 });
    if (!ok) return;

    // Chỉ một phần nhỏ theo bài tới verdict — xem điều 3 ở đầu file.
    if (Math.random() < TI_LE_THEO_DOI) {
        theoToiVerdict(token, res.json('submissionId'), Date.now());
    }
}

/**
 * Chờ verdict bằng cách hỏi lại, giãn dần 250ms → 4s, tối đa 60 giây.
 *
 * Giãn dần chứ không hỏi đều: khi hàng đợi đã dài thì hỏi dày chỉ làm nó dài
 * thêm. Quá 60 giây thì bỏ cuộc và đếm vào `verdict_khong_kip` — bỏ cuộc im
 * lặng sẽ làm p95 đẹp lên đúng ở những lượt chạy tệ nhất.
 */
function theoToiVerdict(token, id, batDau) {
    let cho = 250;
    const han = batDau + HAN_VERDICT_MS;
    while (Date.now() < han) {
        sleep(cho / 1000);
        cho = Math.min(cho * 1.6, 4000);
        const res = http.get(`${BASE}/api/v1/submissions/${id}`,
            { ...tieuDe(token), tags: { viec: 'theo-doi' } });
        if (res.status !== 200) return;
        const tt = res.json('status');
        if (tt !== 'QUEUED' && tt !== 'JUDGING') {
            if (dangDo()) verdictMs.add(Date.now() - batDau);
            return;
        }
    }

    // ★ HẾT HẠN THÌ GHI LẠI, ĐỪNG VỨT.
    //
    // Bản cũ `return` không ghi gì. Hậu quả: tải càng nặng, càng nhiều bài chậm bị loại
    // khỏi mẫu, và p95 càng ĐẸP LÊN. Đo thật ngày 2026-09-05, ba mức liên tiếp:
    //     100 người → P3 14 605ms · 200 người → 62 634ms · 400 người → 59 746ms
    // Tải gấp đôi mà verdict nhanh hơn là chuyện không thể; nó là mẫu bị kiểm duyệt.
    // Số thật từ `submissions.judged_at - created_at`: 14s · 117s · 375s — ô 400 người
    // sai 6,3 lần, và sai theo hướng làm hệ thống trông khoẻ hơn thực tế.
    //
    // Ghi ở giá trị trần biến p95 thành CẬN DƯỚI đúng thay vì một con số bịa. `ket-luan.js`
    // đọc `verdict_cham_tran` rồi in dấu ≥ và tỉ lệ kiểm duyệt, để không ai đọc nó như một
    // phép đo đầy đủ.
    if (dangDo()) {
        verdictMs.add(Date.now() - batDau);
        chamTran.add(1);
    }
    boCuoc.add(1);
}

export function nguoiDung() {
    if (!phien[__VU]) {
        phien[__VU] = dangNhap();
        if (!phien[__VU]) { sleep(5); return; }
    }
    const token = phien[__VU];

    if (Math.random() < TI_LE_NOP) nop(token); else doc(token);

    // Nhịp của người thật, không phải của vòng lặp: 1–3 giây giữa hai thao tác.
    // Rate limit vẫn là thứ chặn thật ở đường nộp — chỗ này chỉ để đừng biến
    // mỗi người ảo thành một máy phát request.
    sleep(1 + Math.random() * 2);
}

/** Dụng cụ đo: một người ảo, mỗi giây một lần, endpoint công khai và rẻ. */
export function giamSat() {
    const res = http.get(`${BASE}/api/v1/status`, { tags: { viec: 'giam-sat' } });
    if (res.status === 200) {
        hangDoi.add(res.json('dangCho'));
        choLauNhat.add(res.json('choLauNhatMs'));
        mayChamSong.add(res.json('mayChamSong'));
    }
    sleep(1);
}

/**
 * Kết luận cuối lượt chạy — nội dung do `ket-luan.js` dựng, xem javadoc ở đó.
 *
 * Giá trị trả về KHÔNG quyết định mã thoát của k6; `options.thresholds` mới quyết định. Hai
 * chỗ ấy phải khớp nhau, nên cả hai cùng nhìn về `nguong.json`: `thresholds` ở trên chép tay
 * ba dòng đầu của `nguong.cung`. Sửa `nguong.json` thì sửa cả đó.
 */
export function handleSummary(data) {
    return tomTat(data, NGUONG, {
        nguoi: NGUOI,
        thoiLuong: THOI_LUONG,
        docLen: DOC_LEN,
        base: BASE,
        tiLeNop: TI_LE_NOP,
        raJson: __ENV.RA_JSON,
        hanVerdictMs: HAN_VERDICT_MS,
    });
}
