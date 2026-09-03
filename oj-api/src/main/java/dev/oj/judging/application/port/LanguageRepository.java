package dev.oj.judging.application.port;

import java.util.Optional;

/**
 * Port đọc bảng {@code languages} cho <b>đường nộp bài</b> (pool {@code app}).
 *
 * <h2>Vì sao đường chấm bài không dùng interface này</h2>
 * Cùng lý do {@code JudgeSpecRepository} tách khỏi {@code ProblemRepository}: hai đường chạy
 * trên hai pool khác nhau. Thông số ngôn ngữ mà worker cần (lệnh biên dịch, hệ số thời gian)
 * đi kèm ngay trong {@code JOIN} của câu claim — xem
 * {@link JudgeQueueRepository.ClaimedJob.LanguageSpec}. Nếu đường claim gọi lại interface
 * này, nó sẽ mượn connection của pool {@code app} đúng lúc pool đó cạn vì 500 người đang nộp
 * bài, và worker không claim được việc ({@code postgres-design.md} mục 11).
 *
 * <p>M3 (Bước 3.1) thay chỗ này bằng {@code LanguageRegistry} có cache — bảng này gần như
 * không bao giờ đổi. Chữ ký không cần đổi theo.
 */
public interface LanguageRepository {

    /**
     * Tra một ngôn ngữ <b>đang bật</b> theo mã ({@code 'cpp20'}, {@code 'py311'}, {@code 'java21'}).
     *
     * <p>Chữ "Enabled" nằm trong tên hàm vì bộ lọc nằm trong câu query. Một hàm tên
     * {@code findByCode} là lời mời cho việc quên kiểm {@code enabled}, và lần quên đó có
     * nghĩa là hệ thống nhận bài bằng một toolchain mà máy chấm không còn cài — mọi bài đều
     * ra {@code IE}, và người dùng không hiểu tại sao.
     *
     * @return rỗng nếu mã không tồn tại <b>hoặc</b> ngôn ngữ đã bị tắt — với người nộp bài,
     *         hai trường hợp đó không khác nhau
     */
    Optional<Language> findEnabledByCode(String code);

    /**
     * Ngôn ngữ đang bật, để giao diện dựng ô chọn — Bước 4.12.
     *
     * <p>★ Tồn tại để giữ đúng NFR M4: <i>"thêm 1 ngôn ngữ chấm = 1 dòng config, 0 dòng
     * code"</i>. Nếu frontend gán cứng ba mã ngôn ngữ thì thêm một ngôn ngữ là <b>hai</b> chỗ
     * phải sửa, và chỗ thứ hai sẽ bị quên — triệu chứng là một ngôn ngữ đã bật mà không ai
     * chọn được.
     *
     * <p>Không phân trang: bảng này có ba dòng và sẽ không bao giờ có nghìn dòng. Đây là một
     * trong rất ít ngoại lệ hợp lệ của bất biến #8, và nó hợp lệ vì <b>số dòng bị chặn bởi
     * bản chất của dữ liệu</b>, không phải bởi hy vọng.
     */
    java.util.List<LanguageOption> listEnabled();

    /**
     * @param versionLabel thứ hiện cho người dùng — "GCC 13.2 / C++20". Người nộp bài cần
     *                     biết phiên bản trình biên dịch, vì nó quyết định cú pháp nào dùng được
     */
    /**
     * ★ {@code id} có mặt ở đây để giao diện tự nối được {@code submissions.languageId}
     * sang tên ngôn ngữ.
     *
     * <h2>Vì sao nối ở client chứ không thêm cột vào DTO bài nộp</h2>
     * {@code SubmissionSummaryResponse} và {@code SubmissionDetailResponse} chỉ mang
     * {@code languageId}, còn danh sách này trước đây chỉ mang {@code code} — không có
     * đường nào nối hai đầu. Triệu chứng: trang chi tiết bài nộp <b>không hiện ngôn ngữ
     * bao giờ</b> (nó né bằng cách bỏ hẳn trường đó), và bộ lọc theo ngôn ngữ mà FR-SUB-07
     * đòi thì không viết được.
     *
     * <p>Cách còn lại là thêm {@code languageCode} vào hai DTO kia — nhưng thế là thêm một
     * {@code JOIN languages} vào truy vấn danh sách bài nộp, tức là vào bảng nóng, cho một
     * bảng tham chiếu ba dòng. Ở đây client tải ba dòng ấy một lần rồi tự nối trong bộ nhớ.
     */
    record LanguageOption(short id, String code, String displayName, String versionLabel) {
    }

    /**
     * Vừa đủ để nhận một bài nộp: {@code languages.id} để ghi vào {@code submissions}, và
     * {@code code} để nhắc lại trong thông báo lỗi.
     *
     * <p>Cố ý <b>không</b> mang lệnh biên dịch hay hệ số thời gian. Đường nộp bài không cần
     * biết những thứ đó, và một trường không được truyền đi thì không thể bị dùng nhầm chỗ.
     */
    record Language(int id, String code) {

        public Language {
            if (id <= 0) {
                throw new IllegalArgumentException("languages.id phải dương");
            }
        }
    }
}
