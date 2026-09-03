#!/usr/bin/env bash
#
# Luật 5d của CodingRulesTest — lớp cuối chống nối chuỗi vào SQL (bất biến #5, SEC2).
#
# ArchUnit ép được 5a/5b/5c, nhưng KHÔNG ép được toán tử `+`: từ Java 9 `a + b` biên dịch
# thành invokedynamic (StringConcatFactory), nên phân tích bytecode không thấy. Chỗ trống ấy
# chỉ bịt được bằng cách đọc mã nguồn — file này.
#
# Chạy được ở máy: ./scripts/kiem-noi-chuoi-sql.sh
# CI gọi đúng file này, nên đỏ ở CI thì tái hiện ở máy trong một giây, không phải một vòng push.
#
# ---------------------------------------------------------------------------------------
# VÌ SAO KHÔNG PHẢI MỘT LỆNH grep '"\s*\+'
#
# Bản đầu của bước này là đúng một dòng grep: bất kỳ dấu `+` nào cạnh một dấu nháy kép đều
# đỏ. Nó bắt được nối chuỗi SQL — và bắt luôn mọi câu log dài xuống dòng, mọi lần ghép
# cursor, mọi thông điệp exception. Bốn dòng đỏ đầu tiên của nó không có dòng nào là SQL.
#
# Một hàng rào bắt nhầm còn nguy hơn không có hàng rào, và nguy theo cách rất cụ thể: người
# ta học được rằng bước này hay đỏ oan, rồi lần nó đỏ ĐÚNG sẽ được xử lý y hệt những lần
# trước — bằng cách nhìn lướt qua. Chính ci.yml đã viết ra nguyên tắc đó ở cuối file ("một CI
# đỏ thường trực là một CI bị bỏ qua"); bước này từng là ngoại lệ cho chính nguyên tắc ấy.
#
# ---------------------------------------------------------------------------------------
# LUẬT MỚI DỰA TRÊN MỘT SỰ THẬT ĐO ĐƯỢC CỦA CÂY MÃ NÀY
#
# Cả 118 câu SQL trong `oj-api` đều là text block (`"""`) gán vào một hằng `static final
# String`, và KHÔNG câu log nào dùng text block. Nghĩa là trong tầng infrastructure,
# "text block" ≈ "SQL" — và bốn luật dưới đây thành ra vừa sắc vừa gọn:
#
#   1. Không dấu `+` nào được chạm vào một text block (trước `"""` mở hoặc sau `"""` đóng).
#   2. Không có `\{...}` (string template) bên trong text block.
#   3. Đối số của `.sql(...)` không được chứa `+`.
#   4. Một literal SQL nằm ngoài text block mà có `+` trên cùng dòng → đỏ.
#
# Luật 4 tồn tại vì luật 1–3 giả định SQL luôn là text block. Nó canh đúng ngày ai đó viết
# câu SQL đầu tiên bằng nháy kép thường — `"SELECT ... WHERE id = " + id` — thay vì giả định
# ngày ấy không tới.
# ---------------------------------------------------------------------------------------
set -euo pipefail

goc="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Tìm MỌI thư mục tên `infrastructure`, ở bất kỳ độ sâu nào.
#
# Bản trước dùng glob `dev/oj/*/infrastructure/` — đúng một mức — nên nó bỏ sót
# `dev/oj/platform/audit/infrastructure/` xuất hiện ở M4.
mapfile -t thu_muc < <(find "$goc/oj-api/src/main/java" -type d -name infrastructure | sort)

if [ ${#thu_muc[@]} -eq 0 ]; then
  echo "::error::Không tìm thấy thư mục infrastructure nào — bước kiểm này đang chạy rỗng"
  exit 1
fi

mapfile -t tep < <(find "${thu_muc[@]}" -name '*.java' -type f | sort)

if [ ${#tep[@]} -eq 0 ]; then
  echo "::error::Không tìm thấy file .java nào trong ${#thu_muc[@]} thư mục infrastructure"
  exit 1
fi

echo "Quét ${#tep[@]} file trong ${#thu_muc[@]} thư mục infrastructure:"
printf '  %s\n' "${thu_muc[@]#"$goc"/}"

awk -v goc="$goc/" '
  function bao(ly_do,   ten) {
    ten = FILENAME; sub("^" goc, "", ten)
    printf "%s:%d: %s\n    %s\n", ten, FNR, ly_do, cat_khoang_trang($0)
    viet_pham++
  }
  function cat_khoang_trang(s) { gsub(/^[ \t]+|[ \t]+$/, "", s); return s }

  # Đối số của .sql(...) — dò ngoặc cân bằng, để `.param("a", b + 1)` phía sau
  # không bị tính nhầm là nối chuỗi vào câu lệnh.
  function doi_so_sql(dong,   i, sau, sau_do, c, tang, ra) {
    i = index(dong, ".sql(")
    if (i == 0) return ""
    sau = substr(dong, i + 5)
    tang = 1; ra = ""
    for (sau_do = 1; sau_do <= length(sau); sau_do++) {
      c = substr(sau, sau_do, 1)
      if (c == "(") tang++
      else if (c == ")") { tang--; if (tang == 0) return ra }
      ra = ra c
    }
    return ra          # chưa đóng ngoặc trên dòng này (thường là `.sql("""` mở text block)
  }

  FNR == 1 { trong_khoi = 0 }

  {
    la_chu_thich = ($0 ~ /^[ \t]*(\*|\/\/|\/\*)/)

    if (trong_khoi) {
      # LUẬT 2 — string template bên trong SQL.
      if ($0 ~ /\\\{/) bao("string template `\\{...}` trong SQL — dùng named parameter :ten")
      if ($0 ~ /"""/) {
        trong_khoi = 0
        # LUẬT 1b — `+` ngay sau text block đóng.
        if ($0 ~ /"""[ \t]*\+/) bao("nối chuỗi ngay sau text block SQL")
      }
      next
    }

    if ($0 ~ /"""/) {
      # LUẬT 1a — `+` ngay trước text block mở.
      if ($0 ~ /\+[ \t]*"""/) bao("nối chuỗi ngay trước text block SQL")
      # `.sql("""` — đối số là chính text block, không có gì thêm để soi.
      trong_khoi = 1
      next
    }

    if (la_chu_thich) next

    # LUẬT 3 — `+` trong đối số của .sql(...)
    if (index($0, ".sql(") > 0) {
      ds = doi_so_sql($0)
      if (ds ~ /\+/) bao("nối chuỗi trong đối số của .sql(...) — dùng named parameter :ten")
    }

    # LUẬT 4 — literal SQL viết bằng nháy thường VÀ có nối chuỗi trên cùng dòng.
    if ($0 ~ /\+/ && toupper($0) ~ /"[ \t]*(SELECT |INSERT INTO |UPDATE |DELETE FROM |WITH )/) {
      bao("nối chuỗi vào một câu SQL viết bằng nháy thường")
    }
  }

  END {
    if (viet_pham > 0) {
      printf "\n::error::%d chỗ nối chuỗi vào SQL trong tầng infrastructure. ", viet_pham
      printf "Mọi câu SQL là hằng text block với named parameter của JdbcClient; "
      printf "cần WHERE động thì viết `(:x IS NULL OR cot = :x)` (bất biến #5, SEC2).\n"
      exit 1
    }
  }
' "${tep[@]}"

echo "OK — không có nối chuỗi nào vào SQL."
