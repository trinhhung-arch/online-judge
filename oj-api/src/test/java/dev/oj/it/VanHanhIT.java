package dev.oj.it;

import dev.oj.judging.application.port.RejudgeRepository;
import dev.oj.judging.application.published.QueueStatusQuery;
import dev.oj.judging.domain.DomainRules;
import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.audit.AuditLogReader;
import dev.oj.platform.settings.SystemSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bốn repository của M6 trên <b>Postgres thật</b> — Bước 6.3, 6.5, 6.11, 6.12.
 *
 * <p>Testcontainers, không H2 ({@code CLAUDE.md} mục 6). Ba trong bốn câu ở đây dùng thứ H2
 * không có: {@code count(*) FILTER (WHERE ...)}, {@code ON CONFLICT DO NOTHING}, và so sánh
 * bộ đôi {@code (occurred_at, id) < (?, ?)}.
 */
class VanHanhIT extends PostgresIT {

    @Autowired
    private RejudgeRepository rejudge;

    @Autowired
    private QueueStatusQuery trangThai;

    @Autowired
    private SystemSettings congTac;

    @Autowired
    private AuditLog nhatKy;

    @Autowired
    private AuditLogReader docNhatKy;

    @Nested
    @DisplayName("★ FR-ADM-01 · đẩy bài trở lại hàng đợi với ưu tiên 10")
    class DayVaoHangDoi {

        @Test
        @DisplayName("đẩy được, đặt priority=10, và đưa submissions về QUEUED")
        void day_duoc() {
            long id = baiDaCham();

            List<Long> vao = rejudge.dayVaoHangDoi(List.of(id));

            assertThat(vao).containsExactly(id);
            assertThat(uuTien(id)).isEqualTo(DomainRules.PRIORITY_REJUDGE);
            assertThat(trangThaiBai(id)).isEqualTo("QUEUED");
        }

        /**
         * ★ Ca này bảo vệ một thứ dễ mất: rejudge một đề ngay sau một đợt nộp dồn <b>không
         * được huỷ lượt chấm đang chạy</b> của chính những bài đó.
         *
         * <p>Không có {@code ON CONFLICT DO NOTHING}, câu {@code INSERT} sẽ vỡ khoá chính và
         * cả lô hỏng — hoặc tệ hơn, nếu ai đó "sửa" nó thành {@code DO UPDATE} thì
         * {@code attempt} của một bài đang được chấm bị ghi đè, và kết quả worker sắp gửi về
         * bị khoá lạc quan từ chối. Một lượt chấm mất trắng, không có lỗi nào được ghi.
         */
        @Test
        @DisplayName("★ bài ĐANG trong hàng đợi thì bỏ qua, không đẩy lần hai")
        void bai_dang_trong_hang_doi_thi_bo_qua() {
            long id = baiDaCham();
            jdbc.sql("INSERT INTO judge_queue (submission_id, priority, attempt) "
                    + "VALUES (:id, 0, 3)").param("id", id).update();

            List<Long> vao = rejudge.dayVaoHangDoi(List.of(id));

            assertThat(vao).isEmpty();
            assertThat(uuTien(id)).as("ưu tiên của bài đang chấm không bị hạ").isZero();
            assertThat(jdbc.sql("SELECT attempt FROM judge_queue WHERE submission_id = :id")
                    .param("id", id).query(Integer.class).single())
                    .as("attempt của bài đang chấm không bị đụng").isEqualTo(3);
        }

        @Test
        @DisplayName("lô rỗng thì không đi hỏi database")
        void lo_rong() {
            assertThat(rejudge.dayVaoHangDoi(List.of())).isEmpty();
        }

        @Test
        @DisplayName("baiCuaDe phân trang theo id tăng dần — điều kiện để job sống sót restart")
        void bai_cua_de_tang_dan() {
            long a = baiDaCham();
            long b = baiDaCham();
            long c = baiDaCham();

            assertThat(rejudge.baiCuaDe(PROBLEM_ID, 0, 2)).containsExactly(a, b);
            assertThat(rejudge.baiCuaDe(PROBLEM_ID, b, 2)).containsExactly(c);
            assertThat(rejudge.baiCuaDe(PROBLEM_ID, c, 2)).isEmpty();
            assertThat(rejudge.demBaiCuaDe(PROBLEM_ID)).isEqualTo(3);
        }

        /**
         * ★ Cái phanh của {@code RejudgeJob} chỉ đúng nếu câu này tách được hai mức ưu tiên.
         * Xem javadoc {@code RejudgeJob} về việc job tự đạp phanh của chính mình.
         */
        @Test
        @DisplayName("★ doNhip đếm rejudge riêng, và KHÔNG lấy thời gian chờ của dòng rejudge")
        void do_nhip_tach_theo_uu_tien() {
            long r = baiDaCham();
            rejudge.dayVaoHangDoi(List.of(r));

            var nhip = rejudge.doNhip();

            assertThat(nhip.rejudgeDangCho()).isEqualTo(1);
            assertThat(nhip.liveChoLauNhat())
                    .as("chỉ có dòng rejudge trong hàng đợi -> KHÔNG có bài live nào chờ")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("FR-ADM-05 · trạng thái hàng đợi — truy vấn 12")
    class TrangThaiHangDoi {

        @Test
        @DisplayName("đếm tách theo chờ / đang chấm / rejudge")
        void dem_tach_ba_nhom() {
            long live = baiDaCham();
            long rj = baiDaCham();
            long dangCham = baiDaCham();
            jdbc.sql("INSERT INTO judge_queue (submission_id, priority, attempt) "
                    + "VALUES (:id, 0, 0)").param("id", live).update();
            rejudge.dayVaoHangDoi(List.of(rj));
            jdbc.sql("INSERT INTO judge_queue (submission_id, priority, attempt, claimed_at, "
                    + "lease_until) VALUES (:id, 0, 1, now(), now() + interval '120 seconds')")
                    .param("id", dangCham).update();

            QueueStatusQuery.TrangThai t = trangThai.doc();

            assertThat(t.dangCho()).isEqualTo(2);
            assertThat(t.dangCham()).isEqualTo(1);
            assertThat(t.rejudgeDangCho()).isEqualTo(1);
            assertThat(t.choLauNhat()).isNotNull();
        }

        @Test
        @DisplayName("hàng đợi rỗng thì rong() và choLauNhat null")
        void hang_doi_rong() {
            QueueStatusQuery.TrangThai t = trangThai.doc();

            assertThat(t.rong()).isTrue();
            assertThat(t.choLauNhat()).isNull();
        }

        /**
         * Máy chấm chuẩn trong seed chưa từng gọi {@code benchmark}, nên {@code last_seen_at}
         * là {@code NULL} và nó <b>không</b> được tính là sống. Đó là hành vi đúng: một máy
         * chưa bao giờ báo danh không phải một máy đang chạy.
         */
        @Test
        @DisplayName("máy chấm chỉ tính là sống khi last_seen_at nằm trong cửa sổ")
        void may_cham_song() {
            assertThat(trangThai.doc().mayChamSong()).isZero();

            jdbc.sql("UPDATE judge_hosts SET last_seen_at = now()").update();

            assertThat(trangThai.doc().mayChamSong()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("FR-ADM-06 · công tắc lúc đang chạy")
    class CongTac {

        @Test
        @DisplayName("đọc được giá trị seed, và đổi thì có hiệu lực NGAY trên instance này")
        void doi_thi_hieu_luc_ngay() {
            assertThat(congTac.bat(SystemSettings.NHAN_BAI_NOP, true)).isTrue();

            congTac.dat(SystemSettings.NHAN_BAI_NOP, false, ADMIN_ID);

            // Cache 2 giây, nhưng dat() xoá cache — nếu không thì một ADMIN bật bảo trì lúc
            // 2 giờ sáng phải chờ, và một công tắc phải chờ là một công tắc không dùng được.
            assertThat(congTac.bat(SystemSettings.NHAN_BAI_NOP, true)).isFalse();
        }

        @Test
        @DisplayName("khoá không tồn tại thì trả mặc định, không ném")
        void khoa_la_thi_tra_mac_dinh() {
            assertThat(congTac.bat("khoa.khong.co.that", true)).isTrue();
            assertThat(congTac.bat("khoa.khong.co.that", false)).isFalse();
        }
    }

    @Nested
    @DisplayName("FR-ADM-02 · đọc audit_log")
    class DocNhatKy {

        @Test
        @DisplayName("mới nhất trước, lọc theo hành động, phân trang bằng (occurred_at, id)")
        void loc_va_phan_trang() {
            for (int i = 0; i < 5; i++) {
                nhatKy.ghi("TEST_ACTION", "problem", (long) i, Map.of("thu", i));
            }
            nhatKy.ghi("HANH_DONG_KHAC", "user", 1L, Map.of());

            var trang1 = docNhatKy.tim(new AuditLogReader.Filter(null, "TEST_ACTION", null, null),
                    null, 2);
            assertThat(trang1.items()).hasSize(2);
            assertThat(trang1.nextCursor()).isNotNull();
            assertThat(trang1.items().get(0).entityId()).isEqualTo(4L);   // mới nhất trước

            var trang2 = docNhatKy.tim(new AuditLogReader.Filter(null, "TEST_ACTION", null, null),
                    trang1.nextCursor(), 2);
            assertThat(trang2.items()).extracting(AuditLogReader.Entry::entityId)
                    .containsExactly(2L, 1L);
        }

        /**
         * ★ Năm dòng ghi trong cùng một mili giây là chuyện thường — một thao tác quản trị ghi
         * ba dòng trong cùng một transaction đều mang {@code now()} giống hệt nhau. Con trỏ
         * chỉ mang {@code id} sẽ bỏ sót dòng ở đúng tình huống đó.
         */
        @Test
        @DisplayName("★ phân trang KHÔNG bỏ sót dòng khi nhiều bản ghi trùng mốc thời gian")
        void trung_moc_thoi_gian_khong_bo_sot() {
            jdbc.sql("""
                    INSERT INTO audit_log (occurred_at, action, entity_type, entity_id)
                    SELECT TIMESTAMPTZ '2026-08-30 10:00:00Z', 'CUNG_LUC', 'problem', g
                      FROM generate_series(1, 10) g
                    """).update();

            var thay = new java.util.ArrayList<Long>();
            String cursor = null;
            for (int trang = 0; trang < 6; trang++) {
                var p = docNhatKy.tim(new AuditLogReader.Filter(null, "CUNG_LUC", null, null),
                        cursor, 3);
                p.items().forEach(e -> thay.add(e.entityId()));
                cursor = p.nextCursor();
                if (cursor == null) {
                    break;
                }
            }

            assertThat(thay).as("10 dòng trùng mốc, phân trang 3 một -> phải thấy đủ 10")
                    .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
        }

        /**
         * ★ Ca hồi quy cho một lỗi thật, tìm ra bởi chính bộ test này chạy cùng các lớp khác.
         *
         * <p>Bản đầu của con trỏ mã hoá thời gian bằng {@code toEpochMilli()}, còn
         * {@code timestamptz} có độ chính xác <b>micro</b> giây. Cắt xuống mili làm con trỏ
         * lùi tới 999µs, và mệnh đề {@code (occurred_at, id) < (:at, :id)} loại luôn những
         * dòng nằm trong cùng mili giây nhưng ở micro giây thấp hơn — tức là <b>trang hai
         * thiếu dòng</b>.
         *
         * <p>Ca {@code trung_moc_thoi_gian_khong_bo_sot} ở trên KHÔNG bắt được nó: mười dòng ở
         * đó trùng nhau tuyệt đối, nên cắt bao nhiêu cũng vô hại. Phải là <i>cùng mili, khác
         * micro</i> — đúng thứ xảy ra khi một thao tác quản trị ghi vài dòng liên tiếp.
         */
        @Test
        @DisplayName("★ cùng MILI giây nhưng khác MICRO giây thì cũng không bỏ sót")
        void cung_mili_khac_micro_khong_bo_sot() {
            for (int i = 0; i < 6; i++) {
                jdbc.sql("""
                        INSERT INTO audit_log (occurred_at, action, entity_type, entity_id)
                        VALUES (TIMESTAMPTZ '2026-08-30 10:00:00.123000Z'
                                + (:us || ' microseconds')::interval,
                                'MICRO', 'problem', :e)
                        """).param("us", i * 100).param("e", (long) i).update();
            }

            var thay = new java.util.ArrayList<Long>();
            String cursor = null;
            for (int trang = 0; trang < 5; trang++) {
                var p = docNhatKy.tim(new AuditLogReader.Filter(null, "MICRO", null, null),
                        cursor, 2);
                p.items().forEach(e -> thay.add(e.entityId()));
                cursor = p.nextCursor();
                if (cursor == null) {
                    break;
                }
            }

            assertThat(thay)
                    .as("sáu dòng cách nhau 100µs, phân trang 2 một -> phải thấy đủ sáu")
                    .containsExactly(5L, 4L, 3L, 2L, 1L, 0L);
        }

        @Test
        @DisplayName("con trỏ hỏng thì quay về trang đầu, không ném")
        void con_tro_hong() {
            nhatKy.ghi("X", "problem", 1L, Map.of());

            assertThat(docNhatKy.tim(AuditLogReader.Filter.none(), "rac-rac-rac", 10).items())
                    .isNotEmpty();
        }
    }

    // -------------------------------------------------------------------------

    /** Một bài nộp {@code DONE}, chưa nằm trong hàng đợi. */
    private long baiDaCham() {
        jdbc.sql("INSERT INTO source_blobs (sha256, content, byte_size) VALUES "
                + "(:sha, 'int main(){}', 12) ON CONFLICT DO NOTHING")
                .param("sha", "a".repeat(64)).update();
        return jdbc.sql("""
                INSERT INTO submissions (user_id, problem_id, language_id, source_sha256,
                                         source_bytes, testdata_version, status, attempt, verdict,
                                         score, max_score, judged_at)
                VALUES (:u, :p, 1, :sha, 12, 1, 'DONE', 1, 'WA', 0, 100, now())
                RETURNING id
                """)
                .param("u", USER_ID).param("p", PROBLEM_ID).param("sha", "a".repeat(64))
                .query(Long.class).single();
    }

    private int uuTien(long submissionId) {
        return jdbc.sql("SELECT priority FROM judge_queue WHERE submission_id = :id")
                .param("id", submissionId).query(Integer.class).single();
    }

    private String trangThaiBai(long submissionId) {
        return jdbc.sql("SELECT status FROM submissions WHERE id = :id")
                .param("id", submissionId).query(String.class).single();
    }
}
