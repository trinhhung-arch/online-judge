-- =============================================================================
-- V4 — Subtask / batch và điểm theo nhóm
-- Mốc: M3 (FR-PROB-06) + M5 (thể thức IOI, FR-CON-06).
--
-- Đây là mức chi tiết SÂU NHẤT mà kết quả chấm được lưu xuống DB. Sâu hơn nữa
-- (kết quả từng test) cố ý không lưu — xem ghi chú (3) ở V3.
-- =============================================================================

CREATE TABLE subtasks (
    id               BIGINT   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    problem_id       BIGINT   NOT NULL,
    testdata_version INTEGER  NOT NULL,
    ordinal          SMALLINT NOT NULL CHECK (ordinal BETWEEN 1 AND 100),
    points           INTEGER  NOT NULL CHECK (points >= 0),
    -- MIN: điểm nhóm = 0 nếu có test fail (kiểu IOI cổ điển)
    -- SUM: cộng điểm từng test trong nhóm
    scoring          TEXT     NOT NULL DEFAULT 'MIN' CHECK (scoring IN ('MIN','SUM')),

    FOREIGN KEY (problem_id, testdata_version)
        REFERENCES testdata_versions(problem_id, version) ON DELETE CASCADE,
    UNIQUE (problem_id, testdata_version, ordinal)
);

-- Phụ thuộc giữa nhóm (FR-PROB-06): nhóm 3 chỉ được chấm nếu nhóm 1,2 đạt.
CREATE TABLE subtask_dependencies (
    subtask_id            BIGINT NOT NULL REFERENCES subtasks(id) ON DELETE CASCADE,
    depends_on_subtask_id BIGINT NOT NULL REFERENCES subtasks(id) ON DELETE CASCADE,
    PRIMARY KEY (subtask_id, depends_on_subtask_id),
    CONSTRAINT ck_subtask_dep_not_self CHECK (subtask_id <> depends_on_subtask_id)
);

ALTER TABLE testcases ADD COLUMN subtask_id BIGINT REFERENCES subtasks(id);
CREATE INDEX ix_testcases_subtask ON testcases (subtask_id) WHERE subtask_id IS NOT NULL;

-- Kết quả theo nhóm của một lần chấm.
CREATE TABLE judge_run_subtasks (
    submission_id       BIGINT   NOT NULL,
    attempt             INTEGER  NOT NULL,
    subtask_ordinal     SMALLINT NOT NULL,
    verdict             TEXT     NOT NULL CHECK (verdict IN ('AC','WA','TLE','MLE','RE','CE','IE','SKIPPED')),
    score               INTEGER  NOT NULL CHECK (score >= 0),
    max_score           INTEGER  NOT NULL CHECK (max_score >= 0),
    failed_test_ordinal SMALLINT,
    time_ms             INTEGER,
    memory_kb           INTEGER,

    PRIMARY KEY (submission_id, attempt, subtask_ordinal),
    FOREIGN KEY (submission_id, attempt)
        REFERENCES judge_runs(submission_id, attempt) ON DELETE CASCADE,
    CONSTRAINT ck_judge_run_subtask_score CHECK (score <= max_score)
);
