package dev.oj.platform.jobs.infrastructure;

import dev.oj.platform.jobs.Job;
import dev.oj.platform.jobs.JobRepository;
import dev.oj.platform.jobs.JobStatus;
import dev.oj.platform.jobs.JobType;
import dev.oj.platform.jobs.JobsException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bảng {@code jobs} và {@code job_events} (V6). Chạy trên pool {@code app}.
 *
 * <h2>★ {@link #claim} là {@code FOR UPDATE SKIP LOCKED}, cùng khuôn với hàng đợi chấm bài</h2>
 * Hai instance API cùng nhặt một job là hai lần nạp cùng một file testdata. {@code SKIP LOCKED}
 * cho instance thứ hai đi thẳng sang job kế tiếp thay vì chờ — cùng lập luận đã dùng ở
 * {@code JdbcJudgeQueueRepository}, và cùng lý do vì sao dự án này chọn Postgres thật thay vì
 * H2 trong test ({@code postgres-design.md} mục 13).
 *
 * <h2>{@code started_at} dùng COALESCE, không ghi đè</h2>
 * Một job bị thu hồi rồi chạy lại phải giữ mốc bắt đầu <b>lần đầu</b>. Ghi đè nó là làm cho
 * "job này chạy bao lâu rồi" trả lời sai đúng vào lúc câu hỏi đó quan trọng nhất — khi có
 * người đang chờ và tự hỏi nó có treo không.
 */
@Repository
public class JdbcJobRepository implements JobRepository {

    private static final String TAO = """
            INSERT INTO jobs (type, params, created_by)
            VALUES (:type, CAST(:params AS jsonb), :createdBy)
            RETURNING id
            """;

    /**
     * Danh sách cột được viết lại trọn vẹn ở mỗi câu, <b>cố ý</b>.
     *
     * <p>Ghép một hằng {@code CHON} với phần {@code WHERE} thì gọn hơn bốn dòng, nhưng nó là
     * nối chuỗi trong tầng {@code infrastructure} — và bước {@code grep} của CI không phân
     * biệt được một phép ghép vô hại với một mệnh đề {@code WHERE} dựng từ dữ liệu người dùng
     * (bất biến #5, SEC2). Bộ lọc ấy thô là <b>đúng</b>: từ Java 9 phép cộng chuỗi biên dịch
     * thành {@code invokedynamic} và ArchUnit không còn thấy nó nữa.
     *
     * <p>Cái giá là sự lặp lại dưới đây. {@code JdbcProblemRepository} trả đúng cái giá ấy
     * cho cùng lý do, và ghi lại trong javadoc của nó.
     */
    private static final String TIM_THEO_ID = """
            SELECT id, type, status, params, cursor_state, total_items, done_items,
                   lease_owner, lease_until, created_by, created_at, started_at,
                   finished_at, error_message
              FROM jobs
             WHERE id = :id
            """;

    private static final String TIM_CHO_NGUOI_GOI = """
            SELECT id, type, status, params, cursor_state, total_items, done_items,
                   lease_owner, lease_until, created_by, created_at, started_at,
                   finished_at, error_message
              FROM jobs
             WHERE id = :id
               AND (:laAdmin OR created_by = :requesterId)
            """;

    private static final String GAN_DAY = """
            SELECT id, type, status, params, cursor_state, total_items, done_items,
                   lease_owner, lease_until, created_by, created_at, started_at,
                   finished_at, error_message
              FROM jobs
             WHERE (:createdBy IS NULL OR created_by = :createdBy)
             ORDER BY id DESC
             LIMIT :gioiHan
            """;

    /**
     * {@code FOR UPDATE SKIP LOCKED} đặt SAU {@code LIMIT} — đó là thứ tự Postgres đòi, và
     * viết ngược lại là một lỗi cú pháp chứ không phải một câu chạy chậm.
     */
    private static final String CLAIM = """
            UPDATE jobs
               SET status = 'RUNNING',
                   lease_owner = :owner,
                   lease_until = :until,
                   heartbeat_at = now(),
                   started_at = COALESCE(started_at, now())
             WHERE id = (SELECT id FROM jobs
                          WHERE status IN ('PENDING', 'PAUSED')
                          ORDER BY id
                          LIMIT 1
                          FOR UPDATE SKIP LOCKED)
            RETURNING id, type, status, params, cursor_state, total_items, done_items,
                      lease_owner, lease_until, created_by, created_at, started_at,
                      finished_at, error_message
            """;

    private static final String NHIP_TIM = """
            UPDATE jobs
               SET done_items = :daXong,
                   total_items = COALESCE(:tong, total_items),
                   lease_until = :leaseMoi,
                   heartbeat_at = now()
             WHERE id = :id AND lease_owner = :owner AND status = 'RUNNING'
            """;

    private static final String LUU_VI_TRI = """
            UPDATE jobs SET cursor_state = CAST(:viTri AS jsonb) WHERE id = :id
            """;

    private static final String THU_HOI_TREO = """
            UPDATE jobs
               SET status = 'PAUSED', lease_owner = NULL, lease_until = NULL
             WHERE status = 'RUNNING' AND lease_until < :bayGio
            """;

    private static final String KET_THUC = """
            UPDATE jobs
               SET status = :status, error_message = :loi, finished_at = :luc,
                   lease_owner = NULL, lease_until = NULL
             WHERE id = :id
            """;

    private static final String HUY = """
            UPDATE jobs
               SET status = 'CANCELLED', finished_at = :luc,
                   lease_owner = NULL, lease_until = NULL
             WHERE id = :id AND status IN ('PENDING', 'RUNNING', 'PAUSED')
            """;

    private static final String GHI_SU_KIEN = """
            INSERT INTO job_events (job_id, level, message)
            VALUES (:jobId, :muc, :thongDiep)
            """;

    private static final String SU_KIEN_GAN_DAY = """
            SELECT at, level, message
              FROM job_events
             WHERE job_id = :jobId
             ORDER BY id DESC
             LIMIT :gioiHan
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcJobRepository(@Qualifier("appJdbcClient") JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public long tao(JobType type, Map<String, Object> params, Long createdBy) {
        try {
            return jdbc.sql(TAO)
                    .param("type", type.name())
                    .param("params", json.writeValueAsString(params == null ? Map.of() : params))
                    .param("createdBy", createdBy)
                    .query(Long.class)
                    .single();
        } catch (DuplicateKeyException e) {
            // ux_jobs_one_active_per_type. Một cú double click trên trang admin là đủ.
            throw JobsException.dangCoJobCungLoai(type);
        }
    }

    @Override
    public Optional<Job> timTheoId(long jobId) {
        return jdbc.sql(TIM_THEO_ID).param("id", jobId).query(mapper()).optional();
    }

    @Override
    public Optional<Job> timChoNguoiGoi(long jobId, long requesterId, boolean laAdmin) {
        return jdbc.sql(TIM_CHO_NGUOI_GOI)
                .param("id", jobId)
                .param("laAdmin", laAdmin)
                .param("requesterId", requesterId)
                .query(mapper())
                .optional();
    }

    @Override
    public List<Job> ganDay(Long createdBy, int gioiHan) {
        return jdbc.sql(GAN_DAY)
                .param("createdBy", createdBy)
                .param("gioiHan", gioiHan)
                .query(mapper())
                .list();
    }

    @Override
    public Optional<Job> claim(String leaseOwner, Instant leaseUntil) {
        return jdbc.sql(CLAIM)
                .param("owner", leaseOwner)
                .param("until", luc(leaseUntil))
                .query(mapper())
                .optional();
    }

    @Override
    public boolean nhipTim(long jobId, String leaseOwner, int daXong, Integer tong,
                           Instant leaseMoi) {
        return jdbc.sql(NHIP_TIM)
                .param("daXong", daXong)
                .param("tong", tong)
                .param("leaseMoi", luc(leaseMoi))
                .param("id", jobId)
                .param("owner", leaseOwner)
                .update() == 1;
    }

    @Override
    public void luuViTri(long jobId, Map<String, Object> viTri) {
        jdbc.sql(LUU_VI_TRI)
                .param("viTri", json.writeValueAsString(viTri == null ? Map.of() : viTri))
                .param("id", jobId)
                .update();
    }

    @Override
    public int thuHoiJobTreo(Instant bayGio) {
        return jdbc.sql(THU_HOI_TREO).param("bayGio", luc(bayGio)).update();
    }

    @Override
    public void ketThuc(long jobId, JobStatus status, String errorMessage, Instant luc) {
        jdbc.sql(KET_THUC)
                .param("status", status.name())
                .param("loi", errorMessage)
                .param("luc", luc(luc))
                .param("id", jobId)
                .update();
    }

    @Override
    public void huy(long jobId, Instant luc) {
        if (jdbc.sql(HUY).param("luc", luc(luc)).param("id", jobId).update() == 0) {
            throw JobsException.daKetThuc();
        }
    }

    @Override
    public void ghiSuKien(long jobId, String muc, String thongDiep) {
        jdbc.sql(GHI_SU_KIEN)
                .param("jobId", jobId)
                .param("muc", muc)
                .param("thongDiep", thongDiep)
                .update();
    }

    @Override
    public List<JobEvent> suKienGanDay(long jobId, int gioiHan) {
        return jdbc.sql(SU_KIEN_GAN_DAY)
                .param("jobId", jobId)
                .param("gioiHan", gioiHan)
                .query((rs, i) -> new JobEvent(
                        rs.getObject("at", OffsetDateTime.class).toInstant(),
                        rs.getString("level"),
                        rs.getString("message")))
                .list();
    }

    private static OffsetDateTime luc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private RowMapper<Job> mapper() {
        return (rs, i) -> new Job(
                rs.getLong("id"),
                JobType.fromCode(rs.getString("type")),
                JobStatus.fromCode(rs.getString("status")),
                doc(rs.getString("params")),
                doc(rs.getString("cursor_state")),
                soNguyen(rs, "total_items"),
                rs.getInt("done_items"),
                rs.getString("lease_owner"),
                thoiDiem(rs, "lease_until"),
                soLon(rs, "created_by"),
                thoiDiem(rs, "created_at"),
                thoiDiem(rs, "started_at"),
                thoiDiem(rs, "finished_at"),
                rs.getString("error_message"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> doc(String jsonb) {
        return jsonb == null ? Map.of() : json.readValue(jsonb, Map.class);
    }

    /** {@code INTEGER} nullable: {@code getInt} trả 0 cho NULL, nên phải hỏi {@code wasNull}. */
    private static Integer soNguyen(ResultSet rs, String cot) throws SQLException {
        int value = rs.getInt(cot);
        return rs.wasNull() ? null : value;
    }

    private static Long soLon(ResultSet rs, String cot) throws SQLException {
        long value = rs.getLong(cot);
        return rs.wasNull() ? null : value;
    }

    private static Instant thoiDiem(ResultSet rs, String cot) throws SQLException {
        OffsetDateTime value = rs.getObject(cot, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
