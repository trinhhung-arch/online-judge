package dev.oj.contests.domain;

import java.time.Duration;
import java.time.Instant;

public record Contest(
        long id,
        String slug,
        String title,
        ContestFormat format,
        Instant startsAt,
        Instant endsAt,
        Instant freezeAt,
        Instant unfrozenAt,
        int penaltyMinutes,
        boolean registrationRequired,
        boolean revealAfterEnd,
        long createdBy) {

    public Contest {
        if (format == null || startsAt == null || endsAt == null) {
            throw new NullPointerException("format, startsAt và endsAt bắt buộc");
        }
        if (!endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("Giờ kết thúc phải sau giờ bắt đầu");
        }
        if (freezeAt != null && (!freezeAt.isAfter(startsAt) || freezeAt.isAfter(endsAt))) {
            throw new IllegalArgumentException(
                    "Giờ đóng băng phải nằm trong khung giờ thi");
        }
        if (penaltyMinutes < 0) {
            throw new IllegalArgumentException("penaltyMinutes không được âm");
        }
    }

    public boolean dangChay(Instant bayGio) {
        return !bayGio.isBefore(startsAt) && bayGio.isBefore(endsAt);
    }

    public boolean chuaMo(Instant bayGio) {
        return bayGio.isBefore(startsAt);
    }

    public boolean daKetThuc(Instant bayGio) {
        return !bayGio.isBefore(endsAt);
    }

    public boolean dangDongBang(Instant bayGio) {
        return freezeAt != null && !bayGio.isBefore(freezeAt) && unfrozenAt == null;
    }

    public boolean daCongBo() {
        return unfrozenAt != null;
    }

    public Duration keTuLucBatDau(Instant luc) {
        return Duration.between(startsAt, luc);
    }
}
