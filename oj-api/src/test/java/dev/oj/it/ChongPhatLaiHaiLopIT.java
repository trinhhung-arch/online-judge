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
        haiLop.bat(USER_ID, 0L);
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
