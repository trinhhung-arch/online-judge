/**
 * Hợp đồng giữa {@code oj-api} và {@code oj-worker}.
 *
 * <h2>Ba luật của package này</h2>
 * <ol>
 *   <li><b>Chỉ JDK.</b> Không Spring, không Jackson, không Lombok. Không gì cả.
 *       ({@code CLAUDE.md} mục 3 luật 4)</li>
 *   <li><b>Không bao giờ chứa nội dung testcase.</b> Chỉ {@code sha256}. Nội dung nằm
 *       content-addressed trên MinIO và chỉ worker tải về — bất biến #1, SEC3.</li>
 *   <li><b>Đóng băng sau tuần 1.</b> Đổi bất cứ chữ ký nào ở đây là một PR chạm cả
 *       {@code oj-api} lẫn {@code oj-worker}, cả hai người duyệt trong cùng một PR
 *       ({@code CLAUDE.md} mục 5.1). Không có ngoại lệ "chỉ thêm một trường thôi".</li>
 * </ol>
 *
 * <h2>Vì sao đóng băng sớm lại đáng</h2>
 * Từ giây phút package này ổn định, A và B làm song song mà không chặn nhau: A viết
 * {@code oj-api} với một worker tưởng tượng, B viết {@code oj-worker} với một API tưởng
 * tượng, và hai bên gặp nhau đúng ở đây. Đó là lý do nó là Bước đầu tiên của M1
 * ({@code docs/build-order.md} Bước M1-1), không phải bước thứ năm.
 *
 * <h2>Hai endpoint dùng package này</h2>
 * <pre>
 *   POST /internal/judge/claim    ClaimRequestDto  → 200 JudgeJobDto | 204 (không có việc)
 *   POST /internal/judge/result   JudgeResultDto   → 204
 *   POST /internal/judge/progress JudgeProgressDto → 204     (M3, lô 20 test)
 * </pre>
 * Cả ba <b>không</b> nằm dưới {@code /api/v1/} và <b>không</b> được lộ ra Cloudflare Tunnel.
 * Xác thực bằng shared secret đọc từ env, không phải JWT người dùng
 * ({@code oj-api/CLAUDE.md} mục 5).
 */
package dev.oj.contract;
