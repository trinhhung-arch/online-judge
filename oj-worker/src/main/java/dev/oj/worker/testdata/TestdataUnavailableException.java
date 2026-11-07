package dev.oj.worker.testdata;

public class TestdataUnavailableException extends RuntimeException {

    public TestdataUnavailableException(String message) {
        super(message);
    }

    public TestdataUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
