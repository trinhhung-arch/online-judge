package dev.oj.worker.testdata;

/**
 * Nguồn testdata ở xa. Một phương thức, và cố ý chỉ có một.
 *
 * <h2>Vì sao là interface chứ không phải một MinIO client</h2>
 * Kho testdata thật ({@code MinioTestdataStore}) là <b>Bước 4.11</b>, tuần 7-9 — ở M2 chưa có
 * gì để tải về. Nhưng {@link TestdataFetcher}, {@link ContentAddressedCache} và toàn bộ đường
 * "nội dung vào chương trình qua stdin" thì phải xong ở M2, vì chúng là thứ giữ bất biến #1.
 *
 * <p>Với seam này thì M4 thêm một hiện thực HTTP trong package {@code client} (luật ArchUnit
 * 7: chỉ {@code client} được nói HTTP) và không sửa một dòng nào ở đây.
 *
 * <p>⚠️ Hiện thực nào cũng <b>không được tin</b> dữ liệu trả về: {@link TestdataFetcher} băm
 * lại và đối chiếu trước khi dùng.
 */
public interface TestdataSource {

    /**
     * @return nội dung, hoặc ném {@link TestdataUnavailableException}. <b>Không bao giờ trả
     *         về nội dung rỗng để "chấm cho xong"</b>
     */
    byte[] fetch(String sha256);
}
