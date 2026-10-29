from pathlib import Path
import unittest

from check_pr import validate


BODY = """## 対応要件
Closes #15。NFR-TEST-02 / DD-RELEASE-02 / TEST-GOV-01
## 変更範囲
CI
## 成果
Debug/Releaseを検証する。
## 証跡
単体テスト成功、実機は対象外。
## 安全・権利・プライバシー
署名鍵を使用しない。
## 未確認
管理者によるmain保護設定。
"""


class CheckPrTest(unittest.TestCase):
    def test_complete_body_passes(self):
        self.assertEqual([], validate(BODY))

    def test_functional_requirement_passes(self):
        self.assertEqual([], validate(BODY.replace("NFR-TEST-02", "FR-PLT-01")))

    def test_null_body_fails(self):
        self.assertTrue(validate(None))

    def test_unfilled_template_fails(self):
        template = Path(__file__).resolve().parents[2] / ".github/pull_request_template.md"
        self.assertTrue(validate(template.read_text(encoding="utf-8")))

    def test_each_required_section_must_have_visible_content(self):
        for section, content in (("変更範囲", "CI"), ("成果", "Debug/Releaseを検証する。"),
                                 ("証跡", "単体テスト成功、実機は対象外。"),
                                 ("安全・権利・プライバシー", "署名鍵を使用しない。"),
                                 ("未確認", "管理者によるmain保護設定。")):
            with self.subTest(section=section):
                self.assertTrue(validate(BODY.replace(content, "<!-- 記入してください -->")))

    def test_each_id_family_is_required(self):
        for identifier in ("NFR-TEST-02", "DD-RELEASE-02", "TEST-GOV-01"):
            with self.subTest(identifier=identifier):
                self.assertTrue(validate(BODY.replace(identifier, "")))

    def test_ids_in_comments_do_not_count(self):
        self.assertTrue(validate(BODY.replace("NFR-TEST-02", "<!-- NFR-TEST-02 -->")))

    def test_ids_in_other_sections_do_not_count(self):
        body = BODY.replace("NFR-TEST-02", "") + "\nNFR-TEST-02\n"
        self.assertTrue(validate(body))

    def test_body_is_data_not_executable(self):
        self.assertEqual([], validate(BODY + "\n$(exit 1) `exit 1` <script>bad()</script>\n"))


if __name__ == "__main__":
    unittest.main()
