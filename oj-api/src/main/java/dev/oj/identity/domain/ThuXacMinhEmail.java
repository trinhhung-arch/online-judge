package dev.oj.identity.domain;

public final class ThuXacMinhEmail {

    private ThuXacMinhEmail() {
    }

    public static String tieuDe() {
        return "Mã xác minh email — Online Judge";
    }

    public static String than(String tenHienThi, String ma, long soPhutConHan) {
        return """
                Chào %s,

                Mã xác minh email của bạn là:

                    %s

                Mã có hiệu lực trong %d phút. Nhập nó ở trang Hồ sơ để xác minh địa chỉ này.

                Nếu bạn không yêu cầu mã này thì bỏ qua thư — tài khoản của bạn vẫn an toàn.
                Mã chỉ dùng được bởi người đang đăng nhập vào chính tài khoản đó.

                — Online Judge
                """.formatted(tenHienThi, ma, soPhutConHan);
    }
}
