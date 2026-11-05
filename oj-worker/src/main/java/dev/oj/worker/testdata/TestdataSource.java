package dev.oj.worker.testdata;

public interface TestdataSource {

    byte[] fetch(String sha256);
}
