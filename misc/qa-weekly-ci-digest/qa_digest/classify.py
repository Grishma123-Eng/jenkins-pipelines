"""Decide whether a red build is a CI/infra problem or a real test/product failure.

Pure regex over the build's causes and console tail. Order matters:
junit failures > infra patterns > test patterns > other.
"""
from __future__ import annotations

import re

INFRA_PATTERNS = [
    r"Agent was removed",
    r"Timeout waiting for agent",
    r"ChannelClosedException",
    r"Backing channel .* is disconnected",
    r"Cannot contact .*: java",
    r"RemovedNodeListener",
    r"Cannot run program \"git\"",
    r"Error cloning remote repo",
    r"No space left on device",
    r"InsufficientInstanceCapacity",
    r"Spot instance .* (interrupt|terminat)",
    r"Could not resolve host",
    r"Temporary failure in name resolution",
    r"curl: \((6|7|28|35|56)\)",
    r"TLS handshake timeout",
    r"toomanyrequests|rate limit exceeded",
    r"Unable to locate credentials",
    r"ssh: connect to host .* (timed out|refused)",
    r"UNREACHABLE!",
    r"There are no nodes with the label",
    r"Waiting for next available executor",
    r"Failed to connect to .* port \d+",
    # molecule could not create its cloud instances (setup, not the product under test)
    r"instance_config\.yml'?\. file not found",
    r"Unable to perform: pro enable",
]
TEST_PATTERNS = [
    r"AssertionError",
    r"\b\d+ failed\b.*\bpassed\b",
    r"FAILED! =>",
    r"failed=[1-9]",
    r"Test .* failed",
    r"\[ fail \]",
]
ERROR_LINE = re.compile(
    "|".join(INFRA_PATTERNS + TEST_PATTERNS + [
        r"^E\s{2,}\S", r"Failed in branch", r"^ERROR:", r"Caused by:", r"fatal:",
        r"Error response from daemon", r"exit code [1-9]",
    ]),
    re.IGNORECASE,
)
_infra = re.compile("|".join(INFRA_PATTERNS), re.IGNORECASE)
_test = re.compile("|".join(TEST_PATTERNS))
_user_abort = re.compile(r"Aborted by \S+")


_scan_test = re.compile(r"CVE-\d|GHSA-", re.IGNORECASE)
_branch = re.compile(r"Failed in branch (.+)")


def classify(build: dict) -> str:
    """Return one of: test, scan, infra, user_abort, other. Only meaningful for red builds.

    scan = only the Trivy/CVE scan part failed; the functional tests passed.
    """
    names = build.get("failed_tests") or []
    log = build.get("log_tail") or ""
    branches = [b.strip() for b in _branch.findall(log)]
    if names:
        return "scan" if all(_scan_test.search(n) for n in names) else "test"
    if branches and all("trivy" in b.lower() for b in branches) and not _test.search(log):
        return "scan"
    if ((build.get("test_counts") or {}).get("fail") or 0) > 0:
        return "test"
    causes = " ".join(build.get("causes") or [])
    if _infra.search(causes) or _infra.search(log):
        return "infra"
    if build.get("result") == "ABORTED" and _user_abort.search(causes):
        return "user_abort"
    if _test.search(log) or any("test" in b.lower() and "trivy" not in b.lower() for b in branches):
        return "test"
    return "other"


_generic = re.compile(r"script returned exit code|^Finished:|^ERROR: script", re.IGNORECASE)
_strong = re.compile("|".join(INFRA_PATTERNS + TEST_PATTERNS + [r"^E\s{2,}\S", r"FAILED!",
                                                                 r"RuntimeError|Exception:"]))


def best_quote(lines: list[str]) -> str:
    """Most telling evidence line: a real error/assertion beats 'exit code 1'."""
    for line in lines:
        if _strong.search(line) and not _generic.search(line):
            return line
    plain = [ln for ln in lines if not _generic.search(ln)]
    # a failed test branch says more than the scan branch next to it
    return next((ln for ln in plain if "trivy" not in ln.lower()), plain[0] if plain else "")


def evidence_lines(log: str, limit: int = 8, width: int = 300) -> list[str]:
    """The last few distinct error-looking lines of a console tail, verbatim (trimmed)."""
    out: list[str] = []
    for line in reversed((log or "").splitlines()):
        line = line.strip()
        if not line or "MaskedException" in line or line.startswith("at "):
            continue
        if ERROR_LINE.search(line) or re.search(r"RuntimeError|Exception:", line):
            line = line[:width]
            if line not in out:
                out.append(line)
        if len(out) >= limit:
            break
    return list(reversed(out))
