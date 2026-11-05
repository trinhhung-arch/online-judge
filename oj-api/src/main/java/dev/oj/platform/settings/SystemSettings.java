package dev.oj.platform.settings;

public interface SystemSettings {

    String NHAN_BAI_NOP = "submissions.accepting";

    String AI_REVIEW = "ai_review.enabled";

    String REJUDGE = "rejudge.enabled";

    boolean bat(String khoa, boolean macDinh);

    void dat(String khoa, boolean giaTri, Long nguoiDoi);
}
