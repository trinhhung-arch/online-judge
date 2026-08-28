package dev.oj.problems.application.port;

import dev.oj.problems.domain.JudgeSpec;

import java.util.Optional;

/**
 * Port đọc thông số chấm bài cho <b>đường verdict</b>. Hiện thực chạy trên pool
 * {@code judge} (6), không phải pool {@code app}.
 *
 * <h2>Vì sao đây là một port riêng thay vì một phương thức của {@link ProblemRepository}</h2>
 * {@code docs/build-order.md} Bước M1-4 gộp {@code findJudgeSpec} vào {@code ProblemRepository}.
 * Viết ra mới thấy không được, vì hai phương thức đó chạy trên hai connection pool khác nhau:
 *
 * <pre>
 *   ProblemRepository    pool app   (20)  ← request người dùng
 *   JudgeSpecRepository  pool judge  (6)  ← CHỈ /internal/judge/claim
 * </pre>
 *
 * <p>Nếu {@code findJudgeSpec} dùng pool {@code app}, thì đúng lúc 500 người nộp bài cùng lúc
 * — tức là đúng lúc pool {@code app} cạn — worker sẽ <b>không claim được job</b>. Đó chính là
 * kịch bản mà việc tách hai pool sinh ra để ngăn ({@code postgres-design.md} mục 11), và nó
 * quay lại qua cửa sau chỉ vì một phương thức đặt nhầm chỗ.
 *
 * <p>Một repository giữ hai {@code JdbcClient} thì che mất chuyện đó. Hai interface với hai
 * cái tên thì {@code ClaimJudgeJobUseCase} chỉ import được đúng một thứ, và nhìn vào là biết
 * nó ở trên đường nào.
 *
 * <p><b>Luật đi kèm:</b> mọi use-case gọi port này phải mang {@code @JudgeTransactional}.
 */
public interface JudgeSpecRepository {

    /**
     * Thông số chấm cho một đề tại <b>đúng một phiên bản testdata</b>.
     *
     * <p>Truyền {@code testdataVersion} lấy từ {@code submissions.testdata_version} — phiên bản
     * mà bài nộp đã ghi lại lúc nộp — chứ không phải {@code problems.current_testdata_version}.
     * Sửa testdata tạo version mới chứ không ghi đè (FR-PROB-10); dùng "mới nhất" ở đây nghĩa
     * là một lần rejudge sẽ âm thầm chấm bài cũ bằng bộ test mới, và không còn đối chiếu được
     * vì sao verdict đổi.
     *
     * @return rỗng nếu đề hoặc phiên bản đó không tồn tại — người gọi trả {@code IE} thay vì
     *         đoán, và reaper sẽ giao lại ({@code oj-worker/CLAUDE.md} mục 6)
     */
    Optional<JudgeSpec> findJudgeSpec(long problemId, int testdataVersion);
}
