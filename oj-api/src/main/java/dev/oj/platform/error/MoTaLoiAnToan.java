package dev.oj.platform.error;

import tools.jackson.core.JacksonException;
import tools.jackson.core.TokenStreamLocation;

final class MoTaLoiAnToan {

    static final int TRAN_DUONG_TRUONG = 80;

    private MoTaLoiAnToan() {
    }

    static String cua(Throwable e) {
        Throwable goc = e;
        JacksonException loiJackson = e instanceof JacksonException j ? j : null;
        while (goc.getCause() != null && goc.getCause() != goc) {
            goc = goc.getCause();
            if (goc instanceof JacksonException j) {
                loiJackson = j;
            }
        }
        StringBuilder moTa = new StringBuilder(goc.getClass().getSimpleName());
        if (loiJackson != null) {
            themViTri(moTa, loiJackson.getLocation());
            String truong = duongTruong(loiJackson);
            if (!truong.isEmpty()) {
                moTa.append(" ở trường ").append(truong);
            }
        }
        return moTa.toString();
    }

    private static void themViTri(StringBuilder moTa, TokenStreamLocation viTri) {
        if (viTri == null) {
            return;
        }
        if (viTri.getLineNr() > 0) {
            moTa.append(" tại dòng ").append(viTri.getLineNr())
                    .append(" cột ").append(viTri.getColumnNr());
        } else if (viTri.getByteOffset() >= 0) {
            moTa.append(" tại byte ").append(viTri.getByteOffset());
        } else if (viTri.getCharOffset() >= 0) {
            moTa.append(" tại ký tự ").append(viTri.getCharOffset());
        }
    }

    private static String duongTruong(JacksonException loi) {
        StringBuilder duong = new StringBuilder();
        for (JacksonException.Reference buoc : loi.getPath()) {
            if (buoc.getPropertyName() != null) {
                duong.append('.').append(buoc.getPropertyName());
            } else if (buoc.getIndex() >= 0) {
                duong.append('[').append(buoc.getIndex()).append(']');
            }
        }
        return duong.length() <= TRAN_DUONG_TRUONG ? duong.toString()
                : duong.substring(0, TRAN_DUONG_TRUONG) + "…";
    }
}
