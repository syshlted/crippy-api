#!/usr/bin/env python3
"""Fail a pull request whose changed lines are not covered (AGENTS.md L4).

crippy's script (crippy D5, roadmap B4), carried across unchanged in what it measures. The
per-module gates stop a module's average sliding; this stops a change *arriving* uncovered while
the average absorbs it, which is the failure a 90%-covered module hides best.

Kover has no diff-coverage feature, which is why this is a script and not a rule in
`build.gradle.kts`. What it does have is a JaCoCo-shaped XML report naming every *executable*
line, which is what separates "this line is not covered" from "this line is a KDoc paragraph".
Java the aidl tool generated never appears in a diff, because the diff is of `*.kt` alone.

Usage:
    diff-coverage.py --base <ref> --head <ref> [--min 85] [--report <path> ...]

Exit status is 0 when the gate passes or when the diff touches no measurable line at all, and 1
when it fails. Anything it cannot do — a missing report, an unparsable diff — is an error rather
than a pass, because a coverage gate that fails open is not a gate.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# Source sets Kover does not instrument, so a changed line in one has no coverage to report and
# must not be counted as uncovered. Tests are the point: they are what covers everything else.
EXCLUDED_SOURCE_SETS = ("/src/test/", "/src/androidTest/", "/src/testFixtures/")

# Every module here has a gate (L4), so none is exempt. crippy exempts `:app`; this repository has
# no module like it.
EXCLUDED_MODULES: tuple[str, ...] = ()

HUNK = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")


def changed_lines(base: str, head: str) -> dict[str, set[int]]:
    """Line numbers added or modified in the *head* revision, per repo-relative path.

    `--unified=0` so a hunk carries no context: context lines are not changed lines, and counting
    them would let a change be carried by whatever happened to sit above it. `--diff-filter=d`
    drops deletions, which have no lines in the head to cover.
    """
    out = subprocess.run(
        ["git", "diff", "--unified=0", "--diff-filter=d", f"{base}...{head}", "--", "*.kt"],
        capture_output=True,
        text=True,
        check=True,
    ).stdout
    result: dict[str, set[int]] = {}
    path: str | None = None
    for line in out.splitlines():
        if line.startswith("+++ b/"):
            path = line[6:]
        elif line.startswith("+++ /dev/null"):
            path = None
        elif path and (match := HUNK.match(line)):
            start = int(match.group(1))
            count = 1 if match.group(2) is None else int(match.group(2))
            if count:
                result.setdefault(path, set()).update(range(start, start + count))
    return result


def measurable(path: str) -> bool:
    if any(marker in f"/{path}" for marker in EXCLUDED_SOURCE_SETS):
        return False
    return not path.startswith(EXCLUDED_MODULES)


def executable_lines(reports: list[Path]) -> dict[str, dict[int, bool]]:
    """Every line Kover measured, keyed by `<package>/<file>.kt`, mapped to whether it was covered.

    Kover's XML gives a package name and a bare file name, not a repo path, so the key here is the
    *suffix* a repo path has to end with. Merging several reports by union is deliberate: a line
    covered by any module's suite is covered, which matters for
    `crippy-contract`, whose hand-written types the client's tests will exercise.
    """
    lines: dict[str, dict[int, bool]] = {}
    for report in reports:
        root = ET.parse(report).getroot()
        for package in root.findall("package"):
            package_name = package.get("name", "")
            for sourcefile in package.findall("sourcefile"):
                key = f"{package_name}/{sourcefile.get('name', '')}"
                target = lines.setdefault(key, {})
                for line in sourcefile.findall("line"):
                    number = int(line.get("nr", "0"))
                    covered = int(line.get("ci", "0")) > 0
                    target[number] = target.get(number, False) or covered
    return lines


def coverage_for(path: str, measured: dict[str, dict[int, bool]]) -> dict[int, bool] | None:
    """The measured lines for a repo path, matched by the `<package>/<file>` suffix Kover reports."""
    for key, lines in measured.items():
        if path.endswith("/" + key):
            return lines
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True, help="merge-base side of the comparison")
    parser.add_argument("--head", required=True, help="revision whose lines must be covered")
    parser.add_argument("--min", type=float, default=85.0, help="percent required (L4 says 85)")
    parser.add_argument(
        "--report",
        action="append",
        default=[],
        help="a Kover XML report; repeatable. Defaults to every */build/reports/kover/report.xml",
    )
    args = parser.parse_args()

    paths = [Path(p) for p in args.report] or sorted(Path().glob("*/build/reports/kover/report.xml"))
    missing = [p for p in paths if not p.is_file()]
    if not paths or missing:
        print(f"::error::no Kover XML report to check against ({missing or 'none found'})")
        print("::error::run `./gradlew koverXmlReport` before this step")
        return 1

    measured = executable_lines(paths)
    changed = changed_lines(args.base, args.head)

    total = 0
    covered = 0
    uncovered_by_file: dict[str, list[int]] = {}
    skipped: list[str] = []

    for path, numbers in sorted(changed.items()):
        if not measurable(path):
            continue
        lines = coverage_for(path, measured)
        if lines is None:
            # Changed Kotlin in a module nothing instruments. Reported rather than ignored: a file
            # silently outside the gate is how a gate stops meaning anything.
            skipped.append(path)
            continue
        for number in sorted(numbers):
            if number not in lines:  # not executable — a comment, a blank, a declaration
                continue
            total += 1
            if lines[number]:
                covered += 1
            else:
                uncovered_by_file.setdefault(path, []).append(number)

    if skipped:
        print("Changed Kotlin with no Kover report, not counted:")
        for path in skipped:
            print(f"  {path}")

    if total == 0:
        print("Diff coverage: no measurable lines changed — nothing to gate.")
        return 0

    percent = 100.0 * covered / total
    print(f"Diff coverage: {covered}/{total} changed executable lines = {percent:.1f}% (need {args.min:.0f}%)")

    if uncovered_by_file:
        print("\nUncovered changed lines:")
        for path, numbers in sorted(uncovered_by_file.items()):
            print(f"  {path}: {', '.join(str(n) for n in numbers)}")

    if percent + 1e-9 < args.min:
        print(f"::error::diff coverage {percent:.1f}% is below L4's {args.min:.0f}% on changed lines")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
