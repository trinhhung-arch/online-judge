package dev.oj.judging.application.port;

import dev.oj.judging.domain.RejudgeJob;

import java.util.List;

public interface RejudgeRepository {

    RejudgeJob.NhipHangDoi doNhip();

    List<Long> baiCuaDe(long problemId, long sauId, int gioiHan);

    List<Long> dayVaoHangDoi(List<Long> ids);

    int demBaiCuaDe(long problemId);
}
