package dev.oj.problems.application.port;

public interface TestdataRepository {

    int phienBanKeTiep(long problemId);

    void taoPhienBan(long problemId, int version, String manifestSha256,
                     int testCount, long totalBytes, long createdBy);

    long themTestcase(long problemId, int version, int ordinal, boolean laSample,
                      String inputSha256, String outputSha256, int inputBytes, int outputBytes);

    void themNoiDungSample(long testcaseId, String input, String output);

    void kichHoatPhienBan(long problemId, int version);

    java.util.Optional<String> shaCuaPhienBan(long problemId, Integer version);
}
