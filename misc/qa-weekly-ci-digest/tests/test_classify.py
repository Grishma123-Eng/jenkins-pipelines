"""Classifier checks built from real console lines (ps80 / pxc / ps57, Nov 2026).

Run:  python -m pytest tests/   (from the qa-weekly-ci-digest folder)
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from qa_digest.classify import best_quote, classify, evidence_lines  # noqa: E402


def b(log="", result="FAILURE", causes=(), failed_tests=None, fail_count=None):
    return {"result": result, "log_tail": log, "causes": list(causes), "failed_tests": failed_tests,
            "test_counts": {"fail": fail_count} if fail_count is not None else None}


def test_trivy_only_is_scan():
    # test-ps-docker-image-5.7-EOL #108: all tests passed, only the scan branch failed
    log = ("======================== 66 passed in 74.69s (0:01:14) =========================\n"
           "Failed in branch Run Trivy Analyzer\nERROR: script returned exit code 1")
    assert classify(b(log)) == "scan"


def test_trivy_plus_assertion_is_test():
    # test-ps-docker-image #244
    log = ("Failed in branch Run trivy analyzer\n"
           "E       AssertionError: assert '8.4.11' == '9.7.2'")
    assert classify(b(log)) == "test"


def test_trivy_plus_failed_test_branch_is_test():
    # test-ps-docker-image #247
    log = "Failed in branch Run trivy analyzer\nFailed in branch Run tests\nERROR: script returned exit code 1"
    assert classify(b(log)) == "test"


def test_cve_only_junit_is_scan():
    # pxb-docker-tests #166: junit cases are trivy findings
    assert classify(b(result="UNSTABLE", failed_tests=[
        "krb5-libs-1.21.1-10.el9_8.1::[HIGH] CVE-2026-107364",
        "oauthlib-3.2.2::[HIGH] CVE-2026-118669"])) == "scan"


def test_git_missing_on_agent_is_infra():
    # test_telemetry_agent #111
    log = ('ERROR: Error cloning remote repo \'origin\'\n'
           'Caused by: java.io.IOException: Cannot run program "git" (in directory "/mnt/x")')
    assert classify(b(log)) == "infra"


def test_molecule_instances_not_created_is_infra():
    # ps-package-testing-molecule #962
    log = ("fatal: [localhost]: FAILED! => {\"msg\": \"The 'file' lookup had an issue accessing the file "
           "'/home/ec2-user/.ansible/tmp/molecule.ZSfJ.oracle-9/instance_config.yml'. file not found\"}")
    assert classify(b(log)) == "infra"


def test_kmip_timeout_is_test():
    # ps-package-testing-molecule #964
    log = ('fatal: [ujammy-964]: FAILED! => {"changed": false, "msg": "Timeout when waiting for 127.0.0.1:5696"}\n'
           "Failed in branch ubuntu-jammy")
    assert classify(b(log)) == "test"


def test_lost_agent_abort_is_infra_user_abort_is_not():
    assert classify(b(result="ABORTED", causes=["Agent was removed", "Timeout waiting for agent to come back"])) == "infra"
    assert classify(b(result="ABORTED", causes=["Aborted by kaushikpuneet07"])) == "user_abort"


def test_best_quote_skips_exit_code_lines():
    lines = evidence_lines("E       AssertionError: assert '0o555' == '0o755'\nERROR: script returned exit code 1")
    assert best_quote(lines).startswith("E       AssertionError")
