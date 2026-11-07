package dev.oj.problems.domain;

public final class TestdataKeys {

    private static final String TIEN_TO = "testdata/";

    private TestdataKeys() {
    }

    public static String khoa(String sha256) {
        if (!hopLe(sha256)) {
            throw new IllegalArgumentException("sha256 phải là 64 ký tự hex thường");
        }
        return TIEN_TO + sha256.substring(0, 2) + "/" + sha256;
    }

    public static boolean hopLe(String sha256) {
        if (sha256 == null || sha256.length() != 64) {
            return false;
        }
        for (int i = 0; i < 64; i++) {
            char c = sha256.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
