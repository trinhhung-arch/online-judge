/**
 * Nháp mã nguồn trong localStorage — FR-SUB-10, Bước 4.12.
 *
 * ★ VÌ SAO NHÁP KHÔNG ĐƯỢC GỬI LÊN SERVER
 *
 * Bản năng là lưu nháp vào database để đồng bộ giữa các máy. Đừng — với một Online Judge thì
 * đó là một tính năng phá hoại:
 *
 *   · Nháp trên server là mã nguồn chưa nộp nằm trong một bảng mà ADMIN đọc được. Trong một
 *     kỳ thi, đó là lời giải của thí sinh trước khi họ nộp.
 *   · Nó tạo một đường ghi mới trên đường nóng: mỗi lần gõ phím là một request.
 *
 * localStorage giữ nháp trong đúng máy đã gõ nó, không ai khác đọc được, và không tốn một
 * byte băng thông nào. Đánh đổi là mất nháp khi đổi máy — và đó là đánh đổi đúng.
 *
 * ★ KHOÁ THEO (ĐỀ, NGÔN NGỮ)
 *
 * Đổi ngôn ngữ không được xoá mất bản C++ đang viết dở. Người ta thử một cách bằng Python rồi
 * quay lại, và mất mã lúc đó là mất công việc thật.
 */

const TIEN_TO = 'oj.nhap.';

function khoa(problemCode, languageCode) {
    return `${TIEN_TO}${problemCode}.${languageCode}`;
}

export function doc(problemCode, languageCode) {
    try {
        return localStorage.getItem(khoa(problemCode, languageCode)) || '';
    } catch {
        return '';   // chế độ riêng tư có thể chặn localStorage — không phải lý do để trang vỡ
    }
}

export function luu(problemCode, languageCode, ma) {
    try {
        if (ma.trim()) localStorage.setItem(khoa(problemCode, languageCode), ma);
        else localStorage.removeItem(khoa(problemCode, languageCode));
    } catch {
        // Hết dung lượng hoặc bị chặn. Nháp là tiện nghi, không phải dữ liệu — im lặng.
    }
}

/**
 * Gọi `luu` sau khi ngừng gõ 800ms.
 *
 * Ghi mỗi lần gõ phím làm chậm trình soạn thảo trên máy yếu; 800ms là đủ ngắn để không mất
 * gì khi đóng tab, đủ dài để không ghi giữa hai phím.
 */
export function hoanLai(fn, cho = 800) {
    let hen;
    return (...args) => {
        clearTimeout(hen);
        hen = setTimeout(() => fn(...args), cho);
    };
}
