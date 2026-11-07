package dev.oj.worker.run.checker;

public interface Checker {

    boolean matches(byte[] expected, byte[] actual);
}
