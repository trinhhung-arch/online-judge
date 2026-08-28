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
