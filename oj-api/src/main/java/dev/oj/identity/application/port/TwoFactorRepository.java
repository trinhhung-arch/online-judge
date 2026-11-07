package dev.oj.identity.application.port;

import dev.oj.identity.domain.TwoFactor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TwoFactorRepository {

    Optional<TwoFactor> tim(long userId);

    boolean luuBanNhap(long userId, String secretEnc);

    void bat(long userId, long buocDaDung);

    boolean ghiBuoc(long userId, long buoc);

    void xoa(long userId);

    void thayMaDuPhong(long userId, List<String> banBam);

    List<MaDuPhong> maDuPhongChuaDung(long userId);

    boolean danhDauDaDung(long maDuPhongId);

    Optional<Instant> khoaHaiLopToi(long userId);

    boolean ghiMaSai(long userId, int nguong, Instant khoaToi);

    void xoaDemMaSai(long userId);

    record MaDuPhong(long id, String codeHash) {
    }
}
