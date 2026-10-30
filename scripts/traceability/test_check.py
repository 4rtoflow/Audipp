import copy
import json
from pathlib import Path
import shutil
import tempfile
import unittest

from scripts.traceability.check import (
    DIRECTORY, ROOT, SOURCE_PATHS, check, inventory, references, validate_records,
)


class InventoryTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for path in SOURCE_PATHS:
            destination = self.root / path
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / path, destination)
        shutil.copytree(ROOT / DIRECTORY, self.root / DIRECTORY)

    def change(self, path, before, after):
        target = self.root / path
        text = target.read_text(encoding='utf-8')
        self.assertIn(before, text)
        target.write_text(text.replace(before, after, 1), encoding='utf-8')

    def test_approved_inventory_matches_sources(self):
        self.assertEqual(check(self.root), [])

    def test_approval_history_and_active_commit_cannot_be_lost(self):
        path = self.root / DIRECTORY / 'baseline.json'
        original = json.loads(path.read_text())
        for field, value in (('approved_history', []), ('active_commit', 'main')):
            with self.subTest(field=field):
                baseline = dict(original, **{field: value})
                path.write_text(json.dumps(baseline))
                self.assertTrue(check(self.root))
        path.write_text(json.dumps(original))

    def test_added_or_removed_requirement_feature_and_test_are_detected(self):
        for name, marker, group in (
            ('01_requirements.md', '| FR-SRC-01 |', 'REQ'),
            ('05_feature_coverage.md', '| [F-01-01]', 'F'),
            ('03_detailed_design.md', '| TEST-LIB-01 |', 'TEST'),
        ):
            path = self.root / 'docs/specifications' / name
            original = path.read_text()
            row = next(line for line in original.splitlines(True) if line.startswith(marker))
            for updated in (original.replace(row, ''), original + row.replace('-01', '-99')):
                with self.subTest(group=group, added=updated.startswith(original)):
                    path.write_text(updated)
                    errors = check(self.root)
                    self.assertTrue(errors)
            path.write_text(original)

    def test_same_count_id_replacement_is_detected(self):
        self.change('docs/specifications/01_requirements.md', '| FR-SRC-01 |', '| FR-SRC-99 |')
        self.assertTrue(check(self.root))

    def test_missing_link_and_missing_target_are_detected(self):
        self.change('docs/specifications/03_detailed_design.md',
                    '| `FR-SRC-01` | DD-MODEL / DD-SRC / DD-UI |',
                    '| `FR-SRC-01` | DD-NONEXISTENT |')
        self.assertTrue(any('missing target DD-NONEXISTENT' in error for error in check(self.root)))
        self.change('docs/specifications/03_detailed_design.md', 'DD-NONEXISTENT |', 'no design |')
        self.assertTrue(any('FR-SRC-01: missing design/test link' in error for error in check(self.root)))

    def test_excluded_and_retired_ids_cannot_become_current(self):
        for identifier, scope in (('FR-PLT-02', 'EXCLUDED'), ('F-13-03.01', 'EXCLUDED'),
                                  ('F-01-02.06', 'RETIRED'), ('F-14-02', 'RETIRED'),
                                  ('P-01', 'PUBLIC')):
            with self.subTest(identifier=identifier):
                path = self.root / DIRECTORY / 'inventory.tsv'
                original = path.read_text()
                row = next(line for line in original.splitlines(True) if line.startswith(identifier + '\t'))
                path.write_text(original.replace(row, row.replace(scope, 'CURRENT')))
                self.assertTrue(check(self.root))
                path.write_text(original)

    def test_approved_text_changes_require_recorded_approval(self):
        self.change('docs/specifications/01_requirements.md', '**現在の完成目標（C/RB）**', '**PUBLICを現在必須とする**')
        self.assertTrue(any('approved source changed' in error for error in check(self.root)))

    def test_windows_line_endings_preserve_the_approved_source(self):
        for path in SOURCE_PATHS:
            target = self.root / path
            target.write_bytes(target.read_bytes().replace(b'\n', b'\r\n'))
        self.assertEqual(check(self.root), [])

    def test_new_retired_design_link_is_rejected(self):
        self.change('docs/specifications/03_detailed_design.md',
                    '| `FR-SRC-02` | DD-SRC / DD-DB |', '| `FR-SRC-02` | DD-PROV-06 |')
        self.assertTrue(any('retired target' in error for error in check(self.root)))

    def test_duplicate_definitions_are_rejected(self):
        path = self.root / 'docs/specifications/01_requirements.md'
        with path.open('a') as stream:
            stream.write('\n| FR-SRC-01 | duplicate |\n')
        with self.assertRaisesRegex(ValueError, 'duplicate definition'):
            inventory(self.root)

    def test_shorthand_references_keep_all_targets(self):
        self.assertEqual(references('TEST-AUD-01/02/03 FR-SRC-08〜10 NFR-A11Y-01'),
                         ['FR-SRC-08', 'FR-SRC-09', 'FR-SRC-10', 'NFR-A11Y-01',
                          'TEST-AUD-01', 'TEST-AUD-02', 'TEST-AUD-03'])

    def test_original_bullet_and_link_must_match(self):
        self.change('docs/specifications/05_feature_coverage.md',
                    '../audio_app_feature_list.md#L11', '../audio_app_feature_list.md#L9999')
        self.assertTrue(any('original feature text/line mismatch' in error for error in check(self.root)))


class ResultTests(unittest.TestCase):
    def setUp(self):
        self.entries = inventory(ROOT)
        self.record = {
            'case_id': 'governance-inventory', 'requirement_ids': ['NFR-TEST-01'],
            'test_ids': ['TEST-GOV-01'], 'commit': 'a' * 40, 'stage': 'CURRENT',
            'outcome': 'PASS', 'execution': 'EXECUTED', 'permission': 'NOT_REQUIRED',
            'reason': 'Inventory mutation cases passed.', 'evidence': ['validation log'],
            'limitations': 'This case does not establish the complete TEST-GOV-01 family.',
        }

    def test_scoped_pass_accepts_evidence(self):
        self.assertEqual(validate_records([self.record], self.entries), [])

    def test_unrun_unestablished_and_unpermitted_cannot_pass(self):
        for field, value in (('execution', 'NOT_RUN'), ('execution', 'UNESTABLISHED'),
                             ('permission', 'UNKNOWN'), ('permission', 'DENIED'),
                             ('evidence', []), ('commit', 'main')):
            with self.subTest(field=field, value=value):
                record = copy.deepcopy(self.record)
                record[field] = value
                self.assertTrue(validate_records([record], self.entries))

    def test_non_success_reasons_can_be_saved(self):
        for outcome, execution, permission in (
            ('NOT_RUN', 'NOT_RUN', 'UNKNOWN'), ('BLOCKED', 'UNESTABLISHED', 'DENIED'),
            ('FAIL', 'EXECUTED', 'NOT_REQUIRED'), ('NOT_APPLICABLE', 'NOT_RUN', 'NOT_REQUIRED'),
        ):
            with self.subTest(outcome=outcome):
                record = dict(self.record, outcome=outcome, execution=execution, permission=permission, evidence=[])
                self.assertEqual(validate_records([record], self.entries), [])

    def test_unknown_excluded_and_unrelated_ids_are_rejected(self):
        for field, value in (('requirement_ids', ['FR-PLT-02']), ('test_ids', ['TEST-UNKNOWN-01']),
                             ('test_ids', ['TEST-AUD-01']), ('test_ids', ['TEST-GOV-01', 'TEST-AUD-01']),
                             ('requirement_ids', [])):
            with self.subTest(field=field, value=value):
                record = dict(self.record, **{field: value})
                self.assertTrue(validate_records([record], self.entries))

    def test_empty_object_and_duplicate_records_are_rejected(self):
        self.assertTrue(validate_records({}, self.entries))
        self.assertTrue(validate_records([self.record, self.record], self.entries))


if __name__ == '__main__':
    unittest.main()
