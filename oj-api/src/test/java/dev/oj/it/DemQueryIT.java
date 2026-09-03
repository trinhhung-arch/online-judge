package dev.oj.it;

import dev.oj.judging.application.usecase.ListMySubmissionsUseCase;
import dev.oj.problems.application.usecase.ListProblemsUseCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ Bước 6.15 — <b>N+1 là lỗi duy nhất không có triệu chứng trong test.</b>
 *
 * <p>Kết quả đúng, mọi khẳng định xanh, chỉ số lượt round-trip tăng tuyến tính theo số dòng.
 * Nó lộ ra trên dữ liệu thật, ở đúng trang người dùng mở nhiều nhất, và thường lộ ra dưới
 * dạng "dạo này site chậm" — một câu không chỉ vào file nào.
 *
 * <p>Cách duy nhất biến nó thành một test đỏ là khẳng định về <b>số lượng</b> truy vấn. Xem
 * {@link DemQuery} về việc vì sao bộ đếm ở đây là {@code java.lang.reflect.Proxy} tám mươi
 * dòng chứ không phải một dependency mới.
 *
 * <h2>Ngưỡng là hằng số nhỏ, KHÔNG phải một hàm của số dòng</h2>
 * Đó là toàn bộ ý nghĩa của những ca dưới đây. Mỗi ca dựng nhiều dòng hơn kích thước trang, và
 * khẳng định số truy vấn <i>không đổi</i>. Một ngưỡng kiểu {@code ≤ n + 1} sẽ xanh với chính
 * cái lỗi nó phải bắt.
 */
class DemQueryIT extends PostgresIT {

    /**
     * Một truy vấn cho danh sách. {@code + 1} là chỗ dư cho một câu đếm hoặc một lần
     * {@code SET} của pool; ba mươi dòng dữ liệu <b>không</b> được làm con số này nhúc nhích.
     */
    private static final int TRAN = 2;

    @Autowired
    private ListMySubmissionsUseCase danhSachBaiNop;

    @Autowired
    private ListProblemsUseCase danhSachDe;

    @AfterEach
    void dungDem() {
        DemQuery.dungDem();
    }

    @Test
    @DisplayName("★ FR-SUB-07 · 30 bài nộp → số truy vấn KHÔNG tăng theo số dòng")
    void danh_sach_bai_nop_khong_n_cong_1() {
        taoBaiNop(30);

        DemQuery.batDau();
        var trang = danhSachBaiNop.list(null, 20,
                dev.oj.judging.application.port.SubmissionRepository.SubmissionFilter.none());
        int soQuery = DemQuery.dem();

        assertThat(trang.items()).hasSize(20);
        assertThat(soQuery)
                .as("20 dòng mà chạy %d truy vấn — mỗi dòng đang đi tra tên đề riêng. "
                        + "Truy vấn 6 của duong_nong.sql đã JOIN sẵn problems.", soQuery)
                .isLessThanOrEqualTo(TRAN);
    }

    @Test
    @DisplayName("★ FR-PROB-01 · 25 đề → vẫn một truy vấn")
    void danh_sach_de_khong_n_cong_1() {
        taoDe(25);

        DemQuery.batDau();
        var trang = danhSachDe.thucHien(null, null, null, 20);
        int soQuery = DemQuery.dem();

        assertThat(trang.items()).hasSize(20);
        assertThat(soQuery).isLessThanOrEqualTo(TRAN);
    }

    /**
     * Bộ đếm phải <b>thật sự đếm</b>. Không có ca này thì một lỗi trong {@link DemQuery} — ví
     * dụ danh sách interface của proxy thiếu {@code DataSource}, hoặc tên phương thức đổi ở
     * một bản JDK sau — làm mọi ca ở trên xanh vĩnh viễn mà không kiểm gì cả.
     */
    @Test
    @DisplayName("★ bộ đếm tự kiểm: một truy vấn tay phải đếm ra ít nhất 1")
    void bo_dem_that_su_dem() {
        DemQuery.batDau();
        jdbc.sql("SELECT 1").query(Integer.class).single();

        assertThat(DemQuery.dem()).isPositive();
    }

    // -------------------------------------------------------------------------

    private void taoBaiNop(int soLuong) {
        jdbc.sql("INSERT INTO source_blobs (sha256, content, byte_size) VALUES "
                + "(:sha, 'int main(){}', 12) ON CONFLICT DO NOTHING")
                .param("sha", "a".repeat(64)).update();
        jdbc.sql("""
                INSERT INTO submissions (user_id, problem_id, language_id, source_sha256,
                                         source_bytes, testdata_version, status, attempt,
                                         verdict, score, max_score, judged_at)
                SELECT :u, :p, 1, :sha, 12, 1, 'DONE', 1, 'AC', 100, 100, now()
                  FROM generate_series(1, :n)
                """)
                .param("u", USER_ID).param("p", PROBLEM_ID).param("sha", "a".repeat(64))
                .param("n", soLuong).update();
    }

    private void taoDe(int soLuong) {
        jdbc.sql("""
                INSERT INTO problems (code, title, statement_md, statement_hash, time_limit_ms,
                                      memory_limit_kb, output_limit_kb, checker_type,
                                      scoring_mode, feedback_level, status, owner_id,
                                      published_at, current_testdata_version)
                SELECT 'DE-' || g, 'Đề ' || g, 'x', :hash, 1000, 262144, 65536, 'token',
                       'ALL_OR_NOTHING', 'TEST_INDEX', 'PUBLISHED', :owner, now(), 1
                  FROM generate_series(1, :n) g
                """)
                .param("hash", "c".repeat(64)).param("owner", SETTER_ID)
                .param("n", soLuong).update();
    }

}
