package dev.oj.identity.infrastructure;

import dev.oj.identity.application.port.EmailVerificationRepository;
import dev.oj.identity.domain.IdentityException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Bảng {@code email_verifications} (V13) — FR-AUTH-09.
 *
 * <p>Pool {@code app}: mọi câu ở đây nằm trên đường request của người dùng, không phải đường
 * verdict. Named parameter khắp nơi (bất biến #5).
 *
 * <h2>★ Mốc thời gian so trong SQL bằng {@code now()}, không so ở Java</h2>
 * Mọi câu hỏi "mã còn sống không" đều là {@code expires_at > now()} trong mệnh đề
 * {@code WHERE}. Đọc dòng lên rồi so với {@code Instant.now()} của JVM là mở ra hai chỗ sai
 * mà không chỗ nào báo: đồng hồ JVM lệch đồng hồ database, và khoảng giữa lúc đọc với lúc
 * ghi. Với database làm trọng tài thì cả hai đều không tồn tại.
 *
 * <h2>★ Không câu nào ở đây đọc hay trả về mã thô</h2>
 * Chỉ có {@code code_sha256}. Mã thô sống trong bộ nhớ của đúng một request — cái request
 * sinh ra nó — rồi đi vào lá thư. Nó không bao giờ quay lại từ database, nên không có đường
 * nào để nó rơi vào một dòng log (bất biến #9).
 */
@Repository
public class JdbcEmailVerificationRepository implements EmailVerificationRepository {

    /** Ba điều kiện của "còn sống" nằm trọn trong một mệnh đề — xem javadoc của port. */
    private static final String TIM_MA_DANG_SONG = """
            SELECT id, code_sha256, created_at, attempts
              FROM email_verifications
             WHERE user_id = :userId
               AND consumed_at IS NULL
               AND expires_at > now()
            """;

    /**
     * KHÔNG có {@code AND expires_at > now()}, và đó là điểm chính của câu này.
     *
     * <p>Mã đã hết hạn vẫn chiếm chỗ trong {@code ux_email_verifications_song} chừng nào
     * {@code consumed_at} còn {@code NULL}. Thêm điều kiện hết hạn vào đây là để lại đúng
     * những dòng ấy, và lần gửi lại sau khi một mã hết hạn sẽ đâm vào unique index —
     * một lỗi chỉ xuất hiện sau đúng 30 phút, tức là không bao giờ gặp lúc đang viết code.
     */
    private static final String HUY_MA_CU = """
            UPDATE email_verifications SET consumed_at = now()
             WHERE user_id = :userId AND consumed_at IS NULL
            """;

    private static final String LUU = """
            INSERT INTO email_verifications (user_id, code_sha256, expires_at)
            VALUES (:userId, :maSha256, :hetHan)
            RETURNING id
            """;

    /** Kiểm-rồi-ghi trong MỘT câu lệnh — xem javadoc của port. */
    private static final String TIEU_THU = """
            UPDATE email_verifications SET consumed_at = now()
             WHERE id = :id
               AND consumed_at IS NULL
               AND expires_at > now()
            """;

    /**
     * {@code attempts = attempts + 1} đọc-và-ghi trong một câu, rồi {@code RETURNING} giá trị
     * mới: hai request gõ sai cùng lúc thì request sau đọc lại giá trị request trước vừa ghi,
     * nên không lượt sai nào biến mất. Đọc lên, cộng ở Java, ghi xuống thì lượt thứ hai ghi
     * đè lượt thứ nhất — và hàng rào chống dò mất một nửa công suất một cách im lặng.
     */
    private static final String GHI_NHAN_THU_SAI = """
            UPDATE email_verifications SET attempts = attempts + 1
             WHERE id = :id AND consumed_at IS NULL
            RETURNING attempts
            """;

    private final JdbcClient jdbc;

    public JdbcEmailVerificationRepository(@Qualifier("appJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<MaDangSong> timMaDangSong(long userId) {
        return jdbc.sql(TIM_MA_DANG_SONG)
                .param("userId", userId)
                .query((rs, n) -> new MaDangSong(
                        rs.getLong("id"),
                        rs.getString("code_sha256"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getInt("attempts")))
                .optional();
    }

    @Override
    public int huyMaCu(long userId) {
        return jdbc.sql(HUY_MA_CU).param("userId", userId).update();
    }

    @Override
    public long luu(long userId, String maSha256, Instant hetHan) {
        try {
            return jdbc.sql(LUU)
                    .param("userId", userId)
                    .param("maSha256", maSha256)
                    .param("hetHan", OffsetDateTime.ofInstant(hetHan, ZoneOffset.UTC))
                    .query(Long.class)
                    .single();
        } catch (DuplicateKeyException e) {
            // ★ Chốt thật của "một người một mã sống". Tới được đây nghĩa là hai request gửi
            // lại chạy song song: cả hai cùng thấy không có mã nào sống, cả hai cùng huỷ, rồi
            // cả hai cùng chèn. Hiếm — nút bị vô hiệu hoá trong lúc gửi — nhưng một client
            // gọi thẳng API thì không có cái nút ấy.
            //
            // Dịch thành 429 chứ không để nó thành 500: người thắng cuộc đua đã có mã trong
            // hộp thư, nên câu đúng để nói với người thua là "vừa gửi rồi, thử lại sau".
            // Cùng khuôn với JdbcUserRepository.taoMoi — unique index là chốt, và chốt thì
            // phải nói được tiếng người.
            throw IdentityException.guiLaiQuaNhanh(Duration.ZERO);
        }
    }

    @Override
    public boolean tieuThu(long id) {
        return jdbc.sql(TIEU_THU).param("id", id).update() > 0;
    }

    @Override
    public int ghiNhanThuSai(long id) {
        // Dòng vừa bị một request khác tiêu thụ thì không có gì để cộng. Trả 0 chứ không ném:
        // phía gọi đang ở nhánh "mã sai" và sẽ từ chối lượt này dù sao đi nữa.
        return jdbc.sql(GHI_NHAN_THU_SAI).param("id", id)
                .query(Integer.class).optional().orElse(0);
    }
}
