#!/usr/bin/env bash
set -euo pipefail

goc="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

thu_muc=()
while IFS= read -r duong; do
  thu_muc+=("$duong")
done < <(find "$goc/oj-api/src/main/java" -type d -name infrastructure | sort)

if [ ${#thu_muc[@]} -eq 0 ]; then
  echo "::error::Không tìm thấy thư mục infrastructure nào — bước kiểm này đang chạy rỗng"
  exit 1
fi

tep=()
while IFS= read -r tep_java; do
  tep+=("$tep_java")
done < <(find "${thu_muc[@]}" -name '*.java' -type f | sort)

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

