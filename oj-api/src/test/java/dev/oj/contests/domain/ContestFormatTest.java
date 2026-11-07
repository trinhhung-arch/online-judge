package dev.oj.contests.domain;

import dev.oj.contests.domain.ContestFormat.BaiDaCham;
import dev.oj.contests.domain.ContestFormat.KetQuaDe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContestFormatTest {

    private static final Instant BAT_DAU = Instant.parse("2026-05-01T09:00:00Z");

    private static Contest contest(String format, int penaltyPhut) {
        return new Contest(1L, "thu", "Thi thử", ContestFormats.tuMa(format),
                BAT_DAU, BAT_DAU.plus(Duration.ofHours(5)), null, null,
                penaltyPhut, true, true, 1L);
    }

    private static BaiDaCham bai(long id, boolean ac, int diem, int phutSauKhiBatDau) {
        return new BaiDaCham(id, 7L, 42L, ac, diem, BAT_DAU.plus(Duration.ofMinutes(phutSauKhiBatDau)));
    }

    @Nested
    @DisplayName("★ ICPC")
    class Icpc {

        private final IcpcFormat format = new IcpcFormat();
        private final Contest c = contest("ICPC", 20);

        private KetQuaDe chay(BaiDaCham... cac) {
            KetQuaDe kq = KetQuaDe.trong(42L);
            for (BaiDaCham b : cac) {
                kq = format.apDung(kq, b, c);
            }
            return kq;
        }

        @Test
        @DisplayName("AC ở lần đầu: 1 đề đạt, penalty = số phút tới lúc AC")
        void ac_ngay_lan_dau() {
            KetQuaDe kq = chay(bai(1, true, 100, 37));

            assertThat(kq.daDat()).isTrue();
            assertThat(kq.soLanSaiTruocKhiDat()).isZero();
            assertThat(format.penaltyGiay(List.of(kq), c)).isEqualTo(37 * 60);
        }

        @Test
        @DisplayName("hai lần sai rồi AC: penalty = phút tới lúc AC + 2 × 20 phút")
        void sai_hai_lan_roi_ac() {
            KetQuaDe kq = chay(bai(1, false, 0, 10), bai(2, false, 0, 20), bai(3, true, 100, 37));

            assertThat(kq.soLanSaiTruocKhiDat()).isEqualTo(2);
            assertThat(format.penaltyGiay(List.of(kq), c))
                    .isEqualTo(37 * 60 + 2 * 20 * 60);
        }

        @Test
        @DisplayName("★ nộp lại SAU khi đã AC không đổi gì — kể cả khi bài sau sai")
        void nop_lai_sau_khi_ac_khong_bi_phat() {
            KetQuaDe sauAc = chay(bai(1, true, 100, 37));
            KetQuaDe sauKhiNopThem = chay(bai(1, true, 100, 37), bai(2, false, 0, 90));

            assertThat(sauKhiNopThem.soLanSaiTruocKhiDat())
                    .isEqualTo(sauAc.soLanSaiTruocKhiDat());
            assertThat(format.penaltyGiay(List.of(sauKhiNopThem), c))
                    .isEqualTo(format.penaltyGiay(List.of(sauAc), c));
            assertThat(sauKhiNopThem.datLuc()).isEqualTo(sauAc.datLuc());
        }

        @Test
        @DisplayName("★ đề chưa đạt KHÔNG đóng penalty nào, dù sai hai mươi lần")
        void chua_dat_thi_khong_penalty() {
            KetQuaDe kq = KetQuaDe.trong(42L);
            for (int i = 1; i <= 20; i++) {
                kq = format.apDung(kq, bai(i, false, 0, i), c);
            }

            assertThat(kq.soLanSaiTruocKhiDat()).isEqualTo(20);
            assertThat(format.penaltyGiay(List.of(kq), c)).isZero();
            assertThat(format.tongHop(List.of(kq)).soBaiDat()).isZero();
        }

        @Test
        @DisplayName("điểm ICPC là số đề đạt, không phải điểm từng phần")
        void diem_la_so_de_dat() {
            KetQuaDe deA = format.apDung(KetQuaDe.trong(1L),
                    new BaiDaCham(1, 7, 1, true, 100, BAT_DAU.plusSeconds(600)), c);
            KetQuaDe deB = format.apDung(KetQuaDe.trong(2L),
                    new BaiDaCham(2, 7, 2, false, 60, BAT_DAU.plusSeconds(600)), c);

            var tong = format.tongHop(List.of(deA, deB));
            assertThat(tong.tongDiem()).isEqualTo(1);
            assertThat(tong.soBaiDat()).isEqualTo(1);
        }

        @Test
        @DisplayName("★ áp cùng một bài hai lần cho cùng kết quả — điều kiện của Quy tắc 4")
        void idempotent() {
            KetQuaDe motLan = chay(bai(1, false, 0, 10), bai(2, true, 100, 37));
            KetQuaDe haiLan = chay(bai(1, false, 0, 10), bai(2, true, 100, 37),
                    bai(2, true, 100, 37));

            assertThat(haiLan).isEqualTo(motLan);
        }

        @Test
        @DisplayName("★ áp lại một bài SAI đã tính không thêm lần sai — ca mà ca trên không thấy")
        void ap_lai_bai_sai_khong_them_lan_sai() {
            KetQuaDe motLan = chay(bai(1, false, 0, 10));
            KetQuaDe haiLan = chay(bai(1, false, 0, 10), bai(1, false, 0, 10));

            assertThat(haiLan).isEqualTo(motLan);
            assertThat(haiLan.soLanSaiTruocKhiDat()).isEqualTo(1);
            assertThat(chay(bai(1, false, 0, 10), bai(1, false, 0, 10), bai(2, true, 100, 37))
                    .soLanSaiTruocKhiDat())
                    .as("penalty sau AC tính đúng một lần sai")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("★ IOI")
    class Ioi {

        private final IoiFormat format = new IoiFormat();
        private final Contest c = contest("IOI", 0);

        private KetQuaDe chay(BaiDaCham... cac) {
            KetQuaDe kq = KetQuaDe.trong(42L);
            for (BaiDaCham b : cac) {
                kq = format.apDung(kq, b, c);
            }
            return kq;
        }

        @Test
        @DisplayName("★ giữ điểm CAO NHẤT — bài sau tệ hơn không xoá kết quả tốt")
        void giu_diem_cao_nhat() {
            KetQuaDe kq = chay(bai(1, false, 70, 10), bai(2, false, 30, 20));

            assertThat(kq.diemCaoNhat()).isEqualTo(70);
            assertThat(kq.baiDatId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("bài tốt hơn thì thay, và mốc thời gian đi theo bài tốt hơn")
        void bai_tot_hon_thi_thay() {
            KetQuaDe kq = chay(bai(1, false, 30, 10), bai(2, false, 90, 40));

            assertThat(kq.diemCaoNhat()).isEqualTo(90);
            assertThat(kq.baiDatId()).isEqualTo(2L);
            assertThat(kq.datLuc()).isEqualTo(BAT_DAU.plus(Duration.ofMinutes(40)));
        }

        @Test
        @DisplayName("★ không có penalty, và số lần nộp không ảnh hưởng gì")
        void khong_co_penalty() {
            KetQuaDe kq = chay(bai(1, false, 10, 5), bai(2, false, 20, 6), bai(3, false, 30, 7));

            assertThat(kq.soLanSaiTruocKhiDat()).isZero();
            assertThat(format.tongHop(List.of(kq)).penaltyGiay()).isZero();
        }

        @Test
        @DisplayName("tổng điểm cộng dồn qua các đề; soBaiDat đếm đề CÓ ĐIỂM")
        void tong_hop_nhieu_de() {
            KetQuaDe a = new KetQuaDe(1L, 100, 0, BAT_DAU.plusSeconds(60), 1L, 1L);
            KetQuaDe b = new KetQuaDe(2L, 40, 0, BAT_DAU.plusSeconds(120), 2L, 2L);
            KetQuaDe c0 = KetQuaDe.trong(3L);

            var tong = format.tongHop(List.of(a, b, c0));
            assertThat(tong.tongDiem()).isEqualTo(140);
            assertThat(tong.soBaiDat()).isEqualTo(2);
            assertThat(tong.lanGhiDiemCuoi()).isEqualTo(BAT_DAU.plusSeconds(120));
        }

        @Test
        @DisplayName("★ áp cùng một bài hai lần cho cùng kết quả")
        void idempotent() {
            assertThat(chay(bai(1, false, 70, 10), bai(1, false, 70, 10)))
                    .isEqualTo(chay(bai(1, false, 70, 10)));
        }

        @Test
        @DisplayName("★ penaltyGiay là 0 qua chính interface — không cần instanceof ở tầng gọi")
        void penalty_bang_khong_qua_interface() {
            ContestFormat qua = format;

            assertThat(qua.penaltyGiay(List.of(new KetQuaDe(1L, 100, 0, BAT_DAU, 1L, 1L)), c))
                    .isZero();
        }
    }

    @Nested
    @DisplayName("Tra thể thức")
    class TraTheThuc {

        @Test
        @DisplayName("mã hợp lệ khớp đúng CHECK của V7")
        void ma_hop_le() {
            assertThat(ContestFormats.maHopLe()).containsExactlyInAnyOrder("ICPC", "IOI");
            assertThat(ContestFormats.tuMa("ICPC")).isInstanceOf(IcpcFormat.class);
            assertThat(ContestFormats.tuMa("IOI")).isInstanceOf(IoiFormat.class);
        }

        @Test
        @DisplayName("mã lạ ném lỗi ồn ào, không im lặng rơi về một thể thức mặc định")
        void ma_la_thi_no() {
            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> ContestFormats.tuMa("CODEFORCES"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
