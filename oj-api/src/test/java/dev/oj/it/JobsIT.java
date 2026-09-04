package dev.oj.it;

import dev.oj.platform.jobs.JobRepository;
import dev.oj.platform.jobs.JobStatus;
import dev.oj.platform.jobs.JobType;
import dev.oj.platform.jobs.JobsException;
import dev.oj.platform.error.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ★ Khung job nền trên Postgres thật — Bước 4.7b (V6, kéo lên từ M6 theo phương án (a)).
 *
 * <h2>Vì sao ba tính chất dưới đây phải kiểm trên database thật</h2>
 * Cả ba đều <b>là</b> ràng buộc của Postgres, không phải mã Java:
 * {@code ux_jobs_one_active_per_type} là một partial unique index, {@code claim} là
 * {@code FOR UPDATE SKIP LOCKED}, và thu hồi job treo dựa vào so sánh {@code TIMESTAMPTZ}.
 * Một bản giả trong bộ nhớ sẽ xanh cho mọi hiện thực, kể cả hiện thực sai.
 */
class JobsIT extends PostgresIT {

    @Autowired JobRepository jobs;

    private long taoJob() {
        return jobs.tao(JobType.TESTDATA_IMPORT, Map.of("problemId", PROBLEM_ID), USER_ID);
    }

    @Nested
    @DisplayName("★ Mỗi loại chỉ một job đang sống")
    class MotJobMoiLoai {

        @Test
        @DisplayName("job thứ hai cùng loại → 409, không phải một dòng thứ hai")
        void chan_job_trung_loai() {
            taoJob();

            assertThatThrownBy(JobsIT.this::taoJob)
                    .isInstanceOf(JobsException.class)
                    .hasFieldOrPropertyWithValue("kind", DomainException.Kind.CONFLICT)
                    .hasFieldOrPropertyWithValue("code", "job.dang_chay");

            assertThat(jdbc.sql("SELECT count(*) FROM jobs").query(Integer.class).single())
                    .describedAs("một cú double click trên trang admin không được tạo hai job")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("job đã kết thúc thì không chiếm chỗ nữa")
        void job_da_xong_khong_chiem_cho() {
            long id = taoJob();
            jobs.ketThuc(id, JobStatus.DONE, null, Instant.now());

            assertThat(taoJob()).isPositive();
        }

        @Test
        @DisplayName("hai LOẠI khác nhau chạy song song được")
        void hai_loai_khac_nhau() {
            taoJob();

            assertThat(jobs.tao(JobType.REJUDGE, Map.of(), USER_ID)).isPositive();
        }
    }

    @Nested
    @DisplayName("★ Danh sách việc — bất biến #8")
    class DanhSachViec {

        private long taoViec(long deId) {
            return jobs.tao(JobType.TESTDATA_IMPORT, Map.of("problemId", deId), ADMIN_ID);
        }

        @Test
        @DisplayName("★ createdBy = null (ADMIN xem tất cả) KHÔNG làm vỡ câu SQL")
        void admin_xem_tat_ca_khong_vo() {
            taoViec(PROBLEM_ID);

            // Đây là lỗi đã sống trên máy chủ thật: `(:createdBy IS NULL OR ...)` không CAST
            // thì Postgres từ chối cả câu lệnh, và `createdBy` là null ĐÚNG khi người gọi là
            // ADMIN. Endpoint trả 500 cho quản trị viên, chạy tốt cho mọi người khác.
            assertThat(jobs.ganDay(null, null, 10))
                    .as("ADMIN phải đọc được việc của mọi người")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("lọc theo người tạo vẫn đúng")
        void loc_theo_nguoi_tao() {
            taoViec(PROBLEM_ID);

            assertThat(jobs.ganDay(ADMIN_ID, null, 10)).isNotEmpty();
            assertThat(jobs.ganDay(USER_ID, null, 10))
                    .as("USER không tạo việc nào thì không thấy việc nào")
                    .isEmpty();
        }

        @Test
        @DisplayName("★ có con trỏ trang — một trần KHÔNG phải là phân trang")
        void con_tro_trang_di_tiep_duoc() {
            long cu = taoViec(PROBLEM_ID);
            jobs.ketThuc(cu, JobStatus.DONE, null, Instant.now());
            long moi = taoViec(PROBLEM_ID);

            var trangDau = jobs.ganDay(null, null, 1);
            assertThat(trangDau).hasSize(1);
            assertThat(trangDau.get(0).id()).as("thứ tự giảm dần: việc mới nhất trước")
                    .isEqualTo(moi);

            assertThat(jobs.ganDay(null, moi, 1))
                    .as("trang sau phải ra việc CŨ hơn, không lặp lại việc vừa trả")
                    .extracting(dev.oj.platform.jobs.Job::id)
                    .containsExactly(cu);
        }
    }

    @Nested
    @DisplayName("★ Claim và lease")
    class ClaimVaLease {

        @Test
        @DisplayName("claim đặt RUNNING và chỉ MỘT instance giành được")
        void chi_mot_instance_gianh_duoc() {
            long id = taoJob();
            Instant han = Instant.now().plus(Duration.ofSeconds(120));

            var mot = jobs.claim("instance-A", han);
            var hai = jobs.claim("instance-B", han);

            assertThat(mot).isPresent();
            assertThat(mot.get().id()).isEqualTo(id);
            assertThat(mot.get().status()).isEqualTo(JobStatus.RUNNING);
            assertThat(hai).describedAs("instance thứ hai phải đi tay không").isEmpty();
        }

        @Test
        @DisplayName("nhịp tim chỉ nhận từ chủ lease — instance khác bị từ chối")
        void nhip_tim_chi_cua_chu_lease() {
            taoJob();
            Instant han = Instant.now().plus(Duration.ofSeconds(120));
            var job = jobs.claim("instance-A", han).orElseThrow();

            assertThat(jobs.nhipTim(job.id(), "instance-A", 5, 10, han)).isTrue();
            assertThat(jobs.nhipTim(job.id(), "instance-B", 99, 10, han))
                    .describedAs("mất lease phải trả false để handler dừng, không chạy song song")
                    .isFalse();

            assertThat(jobs.timTheoId(job.id()).orElseThrow().doneItems()).isEqualTo(5);
        }

        @Test
        @DisplayName("★ lease hết hạn → PAUSED, và chạy tiếp từ cursor_state đã lưu")
        void thu_hoi_job_treo() {
            taoJob();
            Instant daQua = Instant.now().minus(Duration.ofSeconds(1));
            var job = jobs.claim("instance-chet", daQua).orElseThrow();
            jobs.luuViTri(job.id(), Map.of("version", 3));

            assertThat(jobs.thuHoiJobTreo(Instant.now())).isEqualTo(1);

            var nhatLai = jobs.claim("instance-moi", Instant.now().plus(Duration.ofMinutes(2)))
                    .orElseThrow();
            assertThat(nhatLai.id()).isEqualTo(job.id());
            assertThat(nhatLai.cursorState())
                    .describedAs("Quy tắc 5: tiến độ nằm trong DB, nên restart không mất việc")
                    .containsEntry("version", 3);
        }

        @Test
        @DisplayName("started_at giữ mốc LẦN ĐẦU, không bị ghi đè khi chạy lại")
        void started_at_khong_bi_ghi_de() {
            taoJob();
            var lanDau = jobs.claim("A", Instant.now().minusSeconds(1)).orElseThrow();
            jobs.thuHoiJobTreo(Instant.now());
            var lanHai = jobs.claim("B", Instant.now().plusSeconds(120)).orElseThrow();

            assertThat(lanHai.startedAt()).isEqualTo(lanDau.startedAt());
        }
    }

    @Nested
    @DisplayName("Huỷ và tiến độ")
    class HuyVaTienDo {

        @Test
        @DisplayName("huỷ job đã kết thúc → 409")
        void huy_job_da_xong() {
            long id = taoJob();
            jobs.ketThuc(id, JobStatus.DONE, null, Instant.now());

            assertThatThrownBy(() -> jobs.huy(id, Instant.now()))
                    .hasFieldOrPropertyWithValue("code", "job.da_ket_thuc");
        }

        @Test
        @DisplayName("phần trăm là null khi chưa biết tổng, không phải 0")
        void phan_tram_null_khi_chua_biet_tong() {
            long id = taoJob();

            // Một thanh tiến độ đứng ở 0% trông giống hệt một job treo, và người dùng sẽ bấm
            // chạy lại — tạo ra đúng thứ ux_jobs_one_active_per_type phải chặn.
            assertThat(jobs.timTheoId(id).orElseThrow().phanTram()).isNull();

            jobs.claim("A", Instant.now().plusSeconds(120));
            jobs.nhipTim(id, "A", 3, 4, Instant.now().plusSeconds(120));
            assertThat(jobs.timTheoId(id).orElseThrow().phanTram()).isEqualTo(75);
        }

        @Test
        @DisplayName("★ job của người khác không đọc được — 404 chứ không 403")
        void job_cua_nguoi_khac() {
            long id = taoJob();

            assertThat(jobs.timChoNguoiGoi(id, USER_ID, false)).isPresent();
            assertThat(jobs.timChoNguoiGoi(id, SETTER_ID, false))
                    .describedAs("điều kiện chủ sở hữu nằm TRONG câu query")
                    .isEmpty();
            assertThat(jobs.timChoNguoiGoi(id, SETTER_ID, true)).isPresent();
        }

        @Test
        @DisplayName("sự kiện được ghi và đọc lại theo thứ tự mới nhất trước")
        void su_kien() {
            long id = taoJob();
            jobs.ghiSuKien(id, "INFO", "bắt đầu");
            jobs.ghiSuKien(id, "WARN", "một cảnh báo");

            assertThat(jobs.suKienGanDay(id, 10))
                    .extracting(JobRepository.JobEvent::message)
                    .containsExactly("một cảnh báo", "bắt đầu");
        }
    }
}
