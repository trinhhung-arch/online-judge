# 007 · AI review bất đồng bộ, và tắt trong contest

**Bối cảnh.** AI Code Reviewer là điểm khác biệt lớn nhất của đồ án, nhưng nó đụng vào hai thứ
không thoả hiệp: đường chấm bài và tính công bằng.

**Quyết định.** (1) Hàng đợi riêng, ưu tiên thấp, **0ms thêm vào đường chấm** (AI1) — LLM không
bao giờ nằm trên đường verdict. (2) Chỉ chạy khi người dùng **bấm nút**, có quota 5 lượt/ngày.
(3) **Tắt hoàn toàn trong thời gian contest**, kiểm ở tầng use-case theo `contest.status`.
(4) Prompt **chỉ chứa**: đề bài công khai, source của chính user đó, verdict, số thứ tự test fail.

**Lý do.** Một lần LLM chậm nằm trên đường verdict là cả hệ thống chấm bài đứng. Bật AI review
lúc đang thi thì thí sinh nộp bài sai rồi bấm "nhận góp ý" — đó không còn là cuộc thi lập trình.
Và testdata lọt vào prompt là rò rỉ toàn bộ đáp án qua một đường không ai giám sát (rủi ro #3).

**Hệ quả chấp nhận.** Người dùng phải chủ động bấm và chờ. Cần circuit breaker, quota, cost
meter, và 8 test prompt injection trong CI — không được cắt.
