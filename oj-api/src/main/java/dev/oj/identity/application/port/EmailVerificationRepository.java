package dev.oj.identity.application.port;

import java.time.Instant;
import java.util.Optional;

public interface EmailVerificationRepository {

    Optional<MaDangSong> timMaDangSong(long userId);

    int huyMaCu(long userId);

    long luu(long userId, String maSha256, Instant hetHan);

    boolean tieuThu(long id);

    int ghiNhanThuSai(long id);

    record MaDangSong(long id, String sha256Hex, Instant taoLuc, int soLanThu) {
    }
}
