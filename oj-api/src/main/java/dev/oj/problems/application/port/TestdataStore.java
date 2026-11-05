package dev.oj.problems.application.port;

import java.io.InputStream;

public interface TestdataStore {

    void luu(String sha256, InputStream noiDung, long soByte);

    boolean daCo(String sha256);

    InputStream doc(String sha256);
}
