package dev.oj.it;

import dev.oj.judging.application.RejudgeJobHandler;
import dev.oj.judging.application.port.RejudgeRepository;
import dev.oj.judging.domain.DomainRules;
import dev.oj.platform.jobs.JobsException;
import dev.oj.platform.settings.SystemSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * ★ FR-ADM-01 — {@code RejudgeJobHandler} trên Postgres thật. Bước 6.3.
 *
 * <p>Ba thứ chỉ chứng minh được ở đây, không chứng minh được bằng unit test:
 * <b>trần 30% thật sự chặn</b> (nó đọc số dòng trong bảng), <b>job chạy tiếp được sau
 * restart</b> (vị trí nằm trong {@code cursor_state}, không trong bộ nhớ), và <b>verdict cũ
 * không bị ghi đè</b> (ràng buộc của schema).
 */
class RejudgeIT extends PostgresIT {

    @Autowired
    private RejudgeJobHandler handler;

    @Autowired
    private RejudgeRepository rejudge;

    private JobContextGia ctx;

    @BeforeEach
    void dungContext() {
        ctx = new JobContextGia(Map.of(RejudgeJobHandler.THAM_SO_DE, PROBLEM_ID), ADMIN_ID);
    }

    @Test
    @DisplayName("★ trần 30% chặn thật: hàng đợi rejudge không bao giờ vượt max-in-flight")
    void tran_chan_that() {
        taoBaiDaCham(10);

        assertThatExceptionOfType(JobsException.class)
                .isThrownBy(() -> handler.chay(ctx))
                .satisfies(e -> assertThat(e.code())
                        .as("đầy trần thì job NHƯỜNG LƯỢT, không kết thúc")
                        .isEqualTo("job.tam_nghi"));

        assertThat(dangChoRejudge())
                .as("max-in-flight = 2, nên tối đa 2 dòng rejudge chờ cùng lúc")
                .isEqualTo(2);
    }

    /**
     * ★ Quy tắc 5: job phải chạy tiếp được sau restart, và "chạy tiếp" nghĩa là <b>không làm
     * lại từ đầu</b>. Vị trí nằm trong {@code cursor_state}; một handler giữ vị trí trong bộ
     * nhớ sẽ qua được mọi ca khác và đỏ ở ca này.
     */
    @Test
    @DisplayName("★ chạy tiếp từ cursor_state, không đẩy lại bài đã đẩy")
    void chay_tiep_tu_cursor() {
        List<Long> bai = taoBaiDaCham(6);

        chayRoiDonHangDoi();      // lô 1: hai bài đầu
        long viTri1 = viTriDaLuu();

        chayRoiDonHangDoi();      // lô 2: hai bài tiếp
        long viTri2 = viTriDaLuu();

        assertThat(viTri1).isEqualTo(bai.get(1));
        assertThat(viTri2).isEqualTo(bai.get(3));
    }

    /**
     * ★ Tiến độ phải CỘNG DỒN qua các lần tạm nghỉ, không đếm lại từ đầu.
     *
     * <h2>Ca này lấp đúng khe hở mà {@link #chay_tiep_tu_cursor} để lại</h2>
     * Ca kia kiểm vị trí trong {@code cursor_state} tiến đúng — và nó xanh cả khi bộ đếm tiến
     * độ về 0 sau mỗi lần nghỉ, vì hai thứ đó độc lập nhau. Kết quả: công việc chạy đúng và
     * xong đủ, nhưng {@code done_items} luôn hiện số bài của riêng lượt chạy hiện tại.
     *
     * <p>Đo được trên hệ thống đang chạy trước khi sửa: một job chấm lại 34 bài đứng nguyên ở
     * {@code 2/34} suốt thời gian chạy rồi nhảy thẳng lên {@code 34/34} lúc kết thúc. Job này
     * tạm nghỉ theo <b>thiết kế</b> — mỗi lô đẩy nhiều nhất {@code max-in-flight} bài — nên
     * một lượt chấm lại 5000 bài sẽ hiện "2/5000" trong hàng giờ, và người vận hành đọc ra
     * "job đang kẹt" trong khi nó đang chạy tốt. FR-ADM-01 đòi tiến độ.
     */
    @Test
    @DisplayName("★ tiến độ cộng dồn qua các lần tạm nghỉ, không đếm lại từ đầu")
    void tien_do_cong_don_qua_cac_lan_tam_nghi() {
        taoBaiDaCham(6);

        chayRoiDonHangDoi();
        assertThat(ctx.daXongCuoi).as("lô 1").isEqualTo(2);

        chayRoiDonHangDoi();
        assertThat(ctx.daXongCuoi).as("lô 2 — phải là 4, không phải 2 lần nữa").isEqualTo(4);

        chayRoiDonHangDoi();
        assertThat(ctx.daXongCuoi).as("lô 3").isEqualTo(6);

        assertThat(ctx.tongCuoi).as("tổng không đổi giữa các lượt").isEqualTo(6);
    }

    @Test
    @DisplayName("duyệt hết đề thì job kết thúc bình thường, tiến độ bằng tổng")
    void duyet_het_thi_xong() {
        taoBaiDaCham(3);

        for (int i = 0; i < 3; i++) {
            try {
                handler.chay(ctx);
                assertThat(ctx.daXongCuoi).isEqualTo(ctx.tongCuoi);
                return;
            } catch (JobsException e) {
                donHangDoi();     // giả lập worker đã chấm xong lô vừa đẩy
            }
        }
        throw new AssertionError("job không kết thúc sau khi duyệt hết bài");
    }

    /**
     * ★ Chốt (2) của {@code RejudgeJobHandler}: một kỳ thi khai mạc <b>giữa lúc</b> job đang
     * chạy. Chốt lúc tạo job không nói gì về phút thứ 25, và đây là chỗ duy nhất bắt được.
     */
    @Test
    @DisplayName("★ kỳ thi khai mạc giữa chừng thì job tự nhường lượt")
    void ky_thi_khai_mac_giua_chung() {
        taoBaiDaCham(4);
        moKyThiDangChay();

        assertThatExceptionOfType(JobsException.class)
                .isThrownBy(() -> handler.chay(ctx))
                .satisfies(e -> assertThat(e.code()).isEqualTo("job.tam_nghi"));

        assertThat(dangChoRejudge())
                .as("không một bài nào được đẩy trong lúc có kỳ thi")
                .isZero();
    }

    @Test
    @DisplayName("công tắc rejudge.enabled tắt giữa chừng thì dừng ngay")
    void cong_tac_tat_giua_chung() {
        taoBaiDaCham(4);
        congTac.dat(SystemSettings.REJUDGE, false, ADMIN_ID);

        assertThatExceptionOfType(JobsException.class).isThrownBy(() -> handler.chay(ctx));

        assertThat(dangChoRejudge()).isZero();
    }

    /**
     * ★ FR-ADM-01: <i>"verdict cũ không bị ghi đè mà lưu thành attempt mới"</i>.
     *
     * <p>Bài quay về {@code QUEUED} nhưng <b>vẫn giữ verdict cũ</b> — {@code ck_submissions_done}
     * cố ý không ép chiều ngược lại (xem V3), để UI hiện "WA · đang chấm lại" thay vì một ô
     * trống. Và {@code attempt} <b>không</b> bị đụng: lần claim kế tiếp mới tăng.
     */
    @Test
    @DisplayName("★ verdict cũ giữ nguyên, attempt không bị đụng")
    void verdict_cu_giu_nguyen() {
        long id = taoBaiDaCham(1).get(0);
        int attemptTruoc = jdbc.sql("SELECT attempt FROM submissions WHERE id = :id")
                .param("id", id).query(Integer.class).single();

        rejudge.dayVaoHangDoi(List.of(id));

        var sau = jdbc.sql("SELECT status, verdict, attempt FROM submissions WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> List.of(rs.getString(1), rs.getString(2), rs.getInt(3)))
                .single();
        assertThat(sau.get(0)).isEqualTo("QUEUED");
        assertThat(sau.get(1)).as("verdict cũ vẫn còn để UI hiện 'WA · đang chấm lại'")
                .isEqualTo("WA");
        assertThat(sau.get(2)).isEqualTo(attemptTruoc);
    }

    // -------------------------------------------------------------------------

    private void chayRoiDonHangDoi() {
        try {
            handler.chay(ctx);
        } catch (JobsException e) {
            assertThat(e.code()).isEqualTo("job.tam_nghi");
        }
        donHangDoi();
    }

    /** Giả lập worker đã chấm xong: hàng biến khỏi {@code judge_queue}. */
    private void donHangDoi() {
        jdbc.sql("DELETE FROM judge_queue WHERE priority = :p")
                .param("p", DomainRules.PRIORITY_REJUDGE).update();
    }

    private long viTriDaLuu() {
        return ((Number) ctx.viTriDaLuu.get("lastSubmissionId")).longValue();
    }

    private int dangChoRejudge() {
        return jdbc.sql("SELECT count(*) FROM judge_queue WHERE priority = :p")
                .param("p", DomainRules.PRIORITY_REJUDGE).query(Integer.class).single();
    }

    private void moKyThiDangChay() {
        jdbc.sql("""
                INSERT INTO contests (slug, title, format, starts_at, ends_at, created_by)
                VALUES ('dang-thi', 'Đang thi', 'ICPC', now() - interval '1 hour',
                        now() + interval '1 hour', :u)
                """).param("u", ADMIN_ID).update();
    }

    private List<Long> taoBaiDaCham(int soLuong) {
        jdbc.sql("INSERT INTO source_blobs (sha256, content, byte_size) VALUES "
                + "(:sha, 'int main(){}', 12) ON CONFLICT DO NOTHING")
                .param("sha", "a".repeat(64)).update();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < soLuong; i++) {
            ids.add(jdbc.sql("""
                    INSERT INTO submissions (user_id, problem_id, language_id, source_sha256,
                                             source_bytes, testdata_version, status, attempt,
                                             verdict, score, max_score, judged_at)
                    VALUES (:u, :p, 1, :sha, 12, 1, 'DONE', 1, 'WA', 0, 100, :luc)
                    RETURNING id
                    """)
                    .param("u", USER_ID).param("p", PROBLEM_ID).param("sha", "a".repeat(64))
                    .param("luc", OffsetDateTime.now())
                    .query(Long.class).single());
        }
        return ids;
    }
}
