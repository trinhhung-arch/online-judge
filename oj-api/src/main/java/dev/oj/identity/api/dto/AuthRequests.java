package dev.oj.identity.api.dto;

public final class AuthRequests {

    private AuthRequests() {
    }

    public record Register(String handle, String email, String displayName, String password,
                           String captchaToken) {

        @Override
        public String toString() {
            return "Register[handle=" + handle + "]";
        }
    }

    public record Login(String dinhDanh, String password, String maHaiLop) {

        @Override
        public String toString() {
            return "Login[dinhDanh=" + dinhDanh + "]";
        }
    }

    public record XacNhanHaiLop(String ma) {

        @Override
        public String toString() {
            return "XacNhanHaiLop[]";
        }
    }

    public record TatHaiLop(String password, String ma) {

        @Override
        public String toString() {
            return "TatHaiLop[]";
        }
    }

    public record XacMinhEmail(String ma) {

        @Override
        public String toString() {
            return "XacMinhEmail[]";
        }
    }

    public record Refresh(String refreshToken) {

        @Override
        public String toString() {
            return "Refresh[refreshToken=<ẩn>]";
        }
    }

    public record ChangePassword(String matKhauCu, String matKhauMoi) {

        @Override
        public String toString() {
            return "ChangePassword[]";
        }
    }
}
