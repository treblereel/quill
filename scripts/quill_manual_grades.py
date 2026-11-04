"""Validate explicit manual benchmark reviews against the exact saved answers."""
import hashlib
import json
from pathlib import Path


def validate(suite, reviews):
    suite = Path(suite)
    summary = json.loads((suite / 'summary.json').read_text())
    rubric = json.loads((suite / 'rubric.json').read_text())['checks']
    by_key = {review['key']: review for review in reviews}
    if len(by_key) != len(reviews):
        raise ValueError('Duplicate reviews')
    if set(by_key) != {row['key'] for row in summary['records']}:
        raise ValueError('Reviews must cover exactly the measured sessions')
    records = []
    for row in summary['records']:
        answer = suite / 'sessions' / row['key'] / 'turn-01/answer.txt'
        digest = hashlib.sha256(answer.read_bytes()).hexdigest()
        review = by_key[row['key']]
        if review.get('answer_sha256') != digest:
            raise ValueError('Answer changed or not reviewed: ' + row['key'])
        checks = review['checks']
        if [check['criterion'] for check in checks] != rubric[row['case']]:
            raise ValueError('Review does not match frozen rubric: ' + row['key'])
        if any(type(check.get('passed')) is not bool or not check.get('evidence', '').strip() for check in checks):
            raise ValueError('Each criterion needs a boolean verdict and answer evidence')
        records.append(dict(key=row['key'], arm=row['arm'], checks=checks,
                            core_checks_passed=sum(check['passed'] for check in checks),
                            core_checks_total=len(checks), strict_answer_passed=all(check['passed'] for check in checks)))
    return dict(method='Explicit manual reviews bound to answer SHA-256; no inferred passes', records=records,
                arms={arm: dict(answers=len(rows), strict_passed=sum(row['strict_answer_passed'] for row in rows),
                                core_checks_passed=sum(row['core_checks_passed'] for row in rows),
                                core_checks_total=sum(row['core_checks_total'] for row in rows))
                      for arm in summary['arms']
                      for rows in [[row for row in records if row['arm'] == arm]]})


if __name__ == '__main__':
    import sys
    suite = Path(sys.argv[1]).resolve()
    result = validate(suite, json.loads(Path(sys.argv[2]).read_text()))
    (suite / 'grades.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
