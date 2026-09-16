package dev.oj.it;

import dev.oj.identity.application.TotpChecker;
import dev.oj.identity.application.port.SecretCipher;
import dev.oj.identity.application.port.TwoFactorRepository;
import dev.oj.identity.domain.IdentityException;
import dev.oj.identity.domain.MaDuPhong;
import dev.oj.identity.domain.Totp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ Một mã TOTP, một mã dự phòng — dùng được ĐÚNG MỘT LẦN, kể cả khi gửi song song. Postgres thật.
 *
 * <h2>Vì sao phải là Postgres thật</h2>
 * Chốt chống phát lại là <b>một câu {@code UPDATE} có điều kiện</b>, và thứ nó dựa vào là khoá
 * dòng của Postgres: request thứ hai chờ request thứ nhất commit rồi mới đánh giá lại mệnh đề
 * {@code WHERE}. Fake trong bộ nhớ không có khoá dòng nào, nên nó không chứng minh được điều ấy.
 *
 * <p>Bản cũ đọc {@code last_step} (hoặc danh sách mã dự phòng chưa dùng), so trong Java, rồi
 * mới ghi — và ghi vô điều kiện. Mọi request lọt vào giữa lượt đọc và lượt ghi đều qua.
 * Kẻ đứng giữa (proxy, extension trình duyệt) bắt được một mã là dùng lại được nó song song.
 */
class ChongPhatLaiHaiLopIT extends PostgresIT {

    private static final int SO_LUOT = 8;

    @Autowired
    private TotpChecker kiemMa;

    @Autowired
    private TwoFactorRepository haiLop;

    @Autowired
    private SecretCipher maHoa;

    private String biMat;

    @BeforeEach
    void batHaiLopChoDev() {
        biMat = Totp.sinhBiMat();
        haiLop.luuBanNhap(USER_ID, maHoa.maHoa(biMat));
        haiLop.bat(USER_ID, 0L);   // bước 0 là năm 1970: mọi mã hiện tại đều mới hơn nó
    }

    @Test
    @DisplayName("★ cùng một mã TOTP gửi song song → đúng MỘT lượt qua")
    void totp_song_song_chi_mot_luot_qua() throws Exception {
        String ma = maHienTai();

        assertThat(soLuotQua(ma)).isEqualTo(1);
    }

    @Test
    @DisplayName("★ cùng một mã dự phòng gửi song song → đúng MỘT lượt qua")
    void ma_du_phong_song_song_chi_mot_luot_qua() throws Exception {
        String ma = MaDuPhong.sinh();
        haiLop.thayMaDuPhong(USER_ID, List.of(MaDuPhong.bam(ma)));

        assertThat(soLuotQua(ma)).isEqualTo(1);
        assertThat(haiLop.maDuPhongChuaDung(USER_ID)).isEmpty();
    }

    /**
     * Ngữ nghĩa của câu SQL, tách khỏi cuộc đua: bước chỉ được tiến, không được lặp hay lùi.
     * Lùi mà ghi được thì một mã của bước trước (vẫn hợp lệ trong cửa sổ ±1 bước) mở lại được
     * cửa cho mã của bước sau đã dùng.
     */
    @Test
    @DisplayName("ghiBuoc chỉ nhận bước MỚI HƠN — trùng hay lùi đều đổi 0 dòng")
    void ghi_buoc_chi_tien_khong_lui() {
        assertThat(haiLop.ghiBuoc(USER_ID, 100L)).isTrue();
        assertThat(haiLop.ghiBuoc(USER_ID, 100L)).as("cùng bước").isFalse();
        assertThat(haiLop.ghiBuoc(USER_ID, 99L)).as("bước cũ hơn").isFalse();
        assertThat(haiLop.ghiBuoc(USER_ID, 101L)).isTrue();
        assertThat(haiLop.tim(USER_ID).orElseThrow().lastStep()).isEqualTo(101L);
    }

    private int soLuotQua(String ma) throws Exception {
        var chot = new CountDownLatch(1);
        List<Future<Boolean>> hen = new ArrayList<>();
        try (ExecutorService luong = Executors.newFixedThreadPool(SO_LUOT)) {
            for (int i = 0; i < SO_LUOT; i++) {
                hen.add(luong.submit(() -> {
                    chot.await();
                    try {
                        kiemMa.kiem(USER_ID, ma);
                        return true;
                    } catch (IdentityException e) {
                        assertThat(e.code()).isEqualTo("identity.totp_sai");
                        return false;
                    }
                }));
            }
            chot.countDown();
        }
        int qua = 0;
        for (var h : hen) {
            qua += h.get() ? 1 : 0;
        }
        return qua;
    }

    /** Dò mã của đúng bước hiện tại — cùng cách {@code TwoFactorUseCaseTest} làm. */
    private String maHienTai() {
        long giay = Instant.now().getEpochSecond();
        long buoc = Math.floorDiv(giay, Totp.BUOC_GIAY);
        for (int i = 0; i < 1_000_000; i++) {
            String ma = String.format("%06d", i);
            Long khop = Totp.kiem(biMat, ma, giay);
            if (khop != null && khop == buoc) {
                return ma;
            }
        }
        throw new AssertionError("không dò được mã — bản cài đặt Totp hỏng");
    }
}
