"""Check the approved source inventory and scoped execution records, offline."""

import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
DIRECTORY = Path('docs/traceability')
SPECS = 'docs/specifications/'
SOURCE_PATHS = {'docs/audio_app_feature_list.md', 'docs/design.md'} | {
    SPECS + name for name in (
        '00_review_status.md', '01_requirements.md', '02_basic_design.md',
        '03_detailed_design.md', '04_review_2026-10-27.md', '05_feature_coverage.md',
        '06_decisions.md', '07_service_feasibility.md', '08_design_decisions.md',
        '09_private_completion_review.md', '10_provider_proposal_review.md',
        '11_ytdlp_delivery.md',
    )
}
COUNTS = {'REQ': 90, 'F': 229, 'TEST': 43, 'DD': 81, 'QLT': 14,
          'ADR': 8, 'Q': 13, 'R': 7, 'P': 5, 'IMP': 7, 'UAT': 14}
RETIRED = {'F-01-02.02', 'F-01-02.04', 'F-14-02', 'F-14-04',
           'F-01-02.06', 'F-14-13', 'DD-PROV-06'}
EXCLUDED = {'FR-PLT-02', 'F-13-02', 'F-13-03', 'F-13-03.01',
            'F-13-03.02', 'F-13-04', 'F-14-11'}
PUBLIC = {'P-01'}
ID = r'(?:FR|NFR)-[A-Z0-9]+-\d+|DD-[A-Z0-9]+(?:-\d+)?|TEST-[A-Z0-9]+-\d+|F-\d+-\d+(?:\.\d+)?|(?:QLT|ADR|Q|R|P|IMP|UAT)-\d+'
REFERENCE = re.compile(r'(?<![A-Z0-9-])(' + ID + r')((?:(?:/|〜)\d+(?:\.\d+)?)*)')


def references(text):
    result = set()
    for match in REFERENCE.finditer(text):
        identifier, suffix = match.groups()
        result.add(identifier)
        prefix, number = identifier.rsplit('-', 1)
        for separator, end in re.findall(r'(/|〜)(\d+(?:\.\d+)?)', suffix):
            if separator == '〜':
                result.update(f'{prefix}-{n:02d}' for n in range(int(number) + 1, int(end) + 1))
            else:
                result.add(f'{prefix}-{end}')
            number = end
    return sorted(result)


def family(identifier):
    return 'REQ' if identifier.startswith(('FR-', 'NFR-')) else identifier.split('-')[0]


def source_digest(path):
    # Git checkouts may use CRLF; platform line endings are not a requirements change.
    return hashlib.sha256(path.read_text(encoding='utf-8').encode('utf-8')).hexdigest()


def inventory(root):
    entries = {}

    def add(identifier, path, line, links=()):
        if identifier in entries:
            raise ValueError(f'duplicate definition: {identifier}')
        scope = ('RETIRED' if identifier in RETIRED else 'EXCLUDED' if identifier in EXCLUDED
                 else 'PUBLIC' if identifier in PUBLIC else 'CURRENT')
        entries[identifier] = {'id': identifier, 'source': f'{path}#L{line}',
                               'scope': scope, 'links': ','.join(sorted(links))}

    paths = {
        'requirements': SPECS + '01_requirements.md',
        'basic': SPECS + '02_basic_design.md',
        'detail': SPECS + '03_detailed_design.md',
        'features': SPECS + '05_feature_coverage.md',
        'decisions': SPECS + '06_decisions.md',
        'quality': SPECS + '09_private_completion_review.md',
    }
    for kind, path in paths.items():
        for line_number, line in enumerate((root / path).read_text(encoding='utf-8').splitlines(), 1):
            cells = [cell.strip().strip('`') for cell in line.split('|')[1:-1]]
            first = cells[0] if cells else ''
            if kind == 'requirements' and re.fullmatch(r'(?:FR|NFR)-[A-Z0-9]+-\d+|UAT-\d+', first):
                add(first, path, line_number)
            elif kind == 'basic' and re.fullmatch(r'ADR-\d+', first):
                add(first, path, line_number)
            elif kind == 'detail':
                heading = re.match(r'^#{2,3} (DD-[A-Z0-9]+(?:-\d+)?)\b', line)
                if heading:
                    add(heading[1], path, line_number)
                elif re.fullmatch(r'TEST-[A-Z0-9]+-\d+|IMP-\d+', first):
                    add(first, path, line_number)
                elif re.fullmatch(r'(?:FR|NFR)-[A-Z0-9]+-\d+', first):
                    if first not in entries or entries[first]['links']:
                        raise ValueError(f'duplicate or unknown trace row: {first}')
                    entries[first]['links'] = ','.join(references(' '.join(cells[1:])))
            elif kind == 'features':
                feature = re.match(r'\[(F-\d+-\d+(?:\.\d+)?)\]', first)
                if feature:
                    add(feature[1], path, line_number, references(' '.join(cells[2:])))
                elif re.fullmatch(r'F-\d+-\d+(?:\.\d+)?', first):
                    add(first, path, line_number)
            elif kind == 'decisions':
                heading = re.match(r'^### (Q-\d+)：', line)
                if heading:
                    add(heading[1], path, line_number)
                elif re.fullmatch(r'[RP]-\d+', first):
                    add(first, path, line_number)
            elif kind == 'quality':
                quality = re.match(r'QLT-\d+', first)
                if quality:
                    add(quality[0], path, line_number, references(first.split('/', 1)[1]))
    return [entries[key] for key in sorted(entries)]


def read_ledger(root):
    with (root / DIRECTORY / 'inventory.tsv').open(encoding='utf-8', newline='') as stream:
        return list(csv.DictReader(stream, delimiter='\t'))


def ledger_text(entries):
    stream = io.StringIO(newline='')
    writer = csv.DictWriter(stream, fieldnames=('id', 'source', 'links', 'scope'), delimiter='\t', lineterminator='\n')
    writer.writeheader()
    writer.writerows(entries)
    return stream.getvalue()


def validate_origins(root):
    lines = (root / 'docs/audio_app_feature_list.md').read_text(encoding='utf-8').splitlines()
    bullets = {number: re.sub(r'^\s*-\s+', '', line) for number, line in enumerate(lines, 1)
               if re.match(r'^\s*-\s+', line)}
    referenced = []
    errors = []
    for line in (root / SPECS / '05_feature_coverage.md').read_text(encoding='utf-8').splitlines():
        cells = line.split('|')[1:-1]
        if not cells or not re.match(r'\s*\[F-', cells[0]):
            continue
        match = re.fullmatch(r'\s*\[(F-[\d.-]+)\]\(../audio_app_feature_list.md#L(\d+)\)\s*', cells[0])
        if not match:
            errors.append('F: missing original feature link: ' + cells[0].strip())
            continue
        number = int(match[2])
        referenced.append(number)
        if bullets.get(number) != cells[1].strip():
            errors.append(f'{match[1]}: original feature text/line mismatch')
    if len(bullets) != 229 or len(referenced) != 229 or set(referenced) != set(bullets):
        errors.append('F: original 229 bullets must each have exactly one coverage link')
    return errors


def validate_records(records, entries):
    if not isinstance(records, list):
        return ['results must be an array of scoped records']
    errors = []
    known = {entry['id']: entry for entry in entries}
    names = set()
    fields = {'case_id', 'requirement_ids', 'test_ids', 'commit', 'stage', 'outcome',
              'execution', 'permission', 'reason', 'evidence', 'limitations'}
    for record in records:
        name = record.get('case_id', '') if isinstance(record, dict) else ''
        if not isinstance(record, dict) or set(record) != fields:
            errors.append(f'{name}: missing or unknown record fields')
            continue
        if not isinstance(name, str) or not name.strip() or name in names:
            errors.append(f'{name}: case_id must be nonblank and unique')
        if isinstance(name, str):
            names.add(name)
        for field in ('reason', 'limitations'):
            if not isinstance(record[field], str) or not record[field].strip():
                errors.append(f'{name}: {field} must be explicit')
        for field, prefix in (('requirement_ids', ('FR-', 'NFR-')), ('test_ids', ('TEST-',))):
            values = record[field]
            if not isinstance(values, list) or not values or not all(
                isinstance(value, str) and value.startswith(prefix) and value in known
                and known[value]['scope'] == 'CURRENT' for value in values
            ):
                errors.append(f'{name}: {field} must reference current IDs')
        if isinstance(record['requirement_ids'], list) and isinstance(record['test_ids'], list):
            for requirement in record['requirement_ids']:
                if isinstance(requirement, str) and requirement in known:
                    allowed = known[requirement]['links'].split(',')
                    if not any(test in allowed for test in record['test_ids']):
                        errors.append(f'{name}: no related test for {requirement}')
            related_tests = {test for requirement in record['requirement_ids']
                             if isinstance(requirement, str) and requirement in known
                             for test in known[requirement]['links'].split(',')}
            if any(not isinstance(test, str) or test not in related_tests for test in record['test_ids']):
                errors.append(f'{name}: test has no related requirement')
        if not isinstance(record['commit'], str) or not re.fullmatch(r'[0-9a-f]{40}', record['commit']):
            errors.append(f'{name}: commit must be a full SHA')
        if record['stage'] not in ('CURRENT', 'PUBLIC'):
            errors.append(f'{name}: unknown stage')
        if record['outcome'] not in ('PASS', 'FAIL', 'NOT_RUN', 'BLOCKED', 'NOT_APPLICABLE'):
            errors.append(f'{name}: unknown outcome')
        if record['execution'] not in ('EXECUTED', 'NOT_RUN', 'UNESTABLISHED'):
            errors.append(f'{name}: unknown execution')
        if record['permission'] not in ('GRANTED', 'NOT_REQUIRED', 'UNKNOWN', 'DENIED'):
            errors.append(f'{name}: unknown permission')
        evidence = record['evidence']
        if not isinstance(evidence, list) or not all(isinstance(item, str) and item.strip() for item in evidence):
            errors.append(f'{name}: evidence must contain nonblank references')
        if record['outcome'] == 'PASS' and (
            record['execution'] != 'EXECUTED' or record['permission'] not in ('GRANTED', 'NOT_REQUIRED')
            or not evidence
        ):
            errors.append(f'{name}: PASS requires execution, permission and evidence')
    return errors


def validate_baseline(baseline):
    if not isinstance(baseline, dict):
        return ['baseline must be an object']
    history = baseline.get('approved_history')
    if not isinstance(history, list) or len(history) < 2 or not all(isinstance(entry, dict) for entry in history):
        return ['baseline must retain the 1.0.0 and 1.0.1 approval history']
    expected = [('1.0.0', 'cc095bf0d62fb39de197299c1755b5b49c22f239'),
                ('1.0.1', '2adc27605793e9934b1efa7f1bcfac3192a80b19')]
    if [(entry.get('version'), entry.get('commit')) for entry in history[:2]] != expected:
        return ['original approval versions/commits must not be overwritten']
    for entry in history:
        if not all(isinstance(entry.get(field), str) and entry[field].strip()
                   for field in ('version', 'commit', 'date', 'decision_maker', 'scope', 'evidence')):
            return ['approval history must identify version, commit, date, decision maker, scope and evidence']
        if not re.fullmatch(r'[0-9a-f]{40}', entry['commit']):
            return ['approval history commit must be a full SHA']
    if baseline.get('active_commit') != history[-1]['commit']:
        return ['active baseline commit must identify the latest recorded approval/decision']
    return []


def check(root):
    baseline = json.loads((root / DIRECTORY / 'baseline.json').read_text(encoding='utf-8'))
    try:
        entries = inventory(root)
    except ValueError as error:
        return [str(error)]
    errors = []
    errors.extend(validate_baseline(baseline))
    errors.extend(validate_origins(root))
    if baseline['schema_version'] != 1 or set(baseline['source_sha256']) != SOURCE_PATHS:
        errors.append('unsupported baseline schema or incomplete source manifest')
    for path, expected in baseline['source_sha256'].items():
        if source_digest(root / path) != expected:
            errors.append(f'approved source changed: {path}; record the applicable approval/decision before updating the baseline')
    ledger = read_ledger(root)
    if ledger != entries:
        old = {entry['id']: entry for entry in ledger}
        new = {entry['id']: entry for entry in entries}
        changed = sorted(key for key in old.keys() | new.keys() if old.get(key) != new.get(key))
        errors.append('inventory/source mismatch: ' + ', '.join(changed))
    for group, count in COUNTS.items():
        actual = sum(family(entry['id']) == group and entry['scope'] != 'RETIRED' for entry in entries)
        if actual != count or baseline['counts'].get(group) != count:
            errors.append(f'{group}: expected {count}, found {actual}')
    known = {entry['id']: entry for entry in entries}
    if {entry['id'] for entry in entries if entry['scope'] == 'EXCLUDED'} != EXCLUDED:
        errors.append('excluded OS scope changed')
    if {entry['id'] for entry in entries if entry['scope'] == 'RETIRED'} != RETIRED:
        errors.append('retired IDs changed')
    if {entry['id'] for entry in entries if entry['scope'] == 'PUBLIC'} != PUBLIC:
        errors.append('PUBLIC scope changed')
    for entry in entries:
        links = entry['links'].split(',') if entry['links'] else []
        for link in links:
            if link not in known:
                errors.append(f"{entry['id']}: missing target {link}")
            # Preserve this approved-source discrepancy as T18-02, without restoring Apple Music.
            if link in RETIRED and (entry['id'], link) != ('FR-SRC-09', 'DD-PROV-06'):
                errors.append(f"{entry['id']}: retired target {link}; do not restore retired scope")
        if family(entry['id']) in ('REQ', 'F') and entry['scope'] == 'CURRENT':
            if not any(link.startswith('DD-') for link in links) or not any(link.startswith('TEST-') for link in links):
                errors.append(f"{entry['id']}: missing design/test link")
            if family(entry['id']) == 'F' and not any(link.startswith(('FR-', 'NFR-')) for link in links):
                errors.append(f"{entry['id']}: missing requirement link")
    records = json.loads((root / DIRECTORY / 'results.json').read_text(encoding='utf-8'))
    errors.extend(validate_records(records, entries))
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    args = parser.parse_args()
    try:
        errors = check(args.root)
    except (ValueError, KeyError, TypeError, OSError) as error:
        errors = [str(error)]
    for error in errors:
        print(error, file=sys.stderr)
    if not errors:
        print('Approved inventory OK: 90 requirements / 229 features / 43 test families; execution completion is not inferred.')
        print('OPEN T18-02: FR-SRC-09 still cites retired DD-PROV-06 in the approved source; Apple Music remains excluded.')
    return bool(errors)


if __name__ == '__main__':
    sys.exit(main())
