package dev.oj.contests.domain;

import java.util.List;

public final class ContestFormats {

    private static final List<ContestFormat> TAT_CA = List.of(new IcpcFormat(), new IoiFormat());

    private ContestFormats() {
    }

    public static ContestFormat tuMa(String code) {
        for (ContestFormat f : TAT_CA) {
            if (f.code().equals(code)) {
                return f;
            }
        }
        throw new IllegalArgumentException("Thể thức không hợp lệ: " + code);
    }

    public static List<String> maHopLe() {
        return TAT_CA.stream().map(ContestFormat::code).toList();
    }
}
