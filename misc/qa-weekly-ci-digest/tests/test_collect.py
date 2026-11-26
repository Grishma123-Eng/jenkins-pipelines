"""Collector check against a fake Jenkins API (no network)."""
import sys
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from qa_digest import jenkins, stats  # noqa: E402

NOW = 1_795_000_000_000
DAY = 86_400_000


class Resp:
    def __init__(self, status=200, json_data=None, text=""):
        self.status_code, self._j, self.text = status, json_data, text
        self.ok = status < 400
        self.headers = {}

    def json(self):
        return self._j

    def raise_for_status(self):
        if not self.ok:
            raise RuntimeError(self.status_code)

    def iter_content(self, chunk_size):
        data = self.text.encode()
        for i in range(0, len(data), chunk_size):
            yield data[i:i + chunk_size]

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


def build(n, result, days_ago, job="pxc-docker-tests", upstream=None):
    cause = ({"shortDescription": f"Started by upstream project {upstream[0]}",
              "upstreamProject": upstream[0], "upstreamBuild": upstream[1]}
             if upstream else {"shortDescription": "Started by user grishma"})
    return {"number": n, "result": result, "timestamp": NOW - days_ago * DAY, "duration": 600_000,
            "url": f"https://pxc.cd.percona.com/job/{job}/{n}/", "building": False,
            "actions": [{"causes": [cause]}]}


class FakeSession:
    auth = None

    def get(self, url, params=None, timeout=None, stream=False):
        if url.endswith("/api/json") and "allBuilds" in (params or {}).get("tree", ""):
            if "/job/pxc-docker-tests/" in url:
                return Resp(json_data={"allBuilds": [build(107, "FAILURE", 1), build(104, "SUCCESS", 3),
                                                     build(90, "FAILURE", 80)]})
            if "/job/pxc-package-testing-parallel/" in url:
                return Resp(json_data={"allBuilds": [build(301, "UNSTABLE", 2, job="pxc-package-testing-parallel")]})
            if "/job/pxc-package-testing/" in url:
                return Resp(json_data={"allBuilds": [
                    build(5157, "UNSTABLE", 2, job="pxc-package-testing",
                          upstream=("pxc-package-testing-parallel", 301)),
                    build(5101, "SUCCESS", 2, job="pxc-package-testing",
                          upstream=("pxc-package-testing-parallel-test", 44))]})
            return Resp(json_data={"allBuilds": []})
        if url.endswith("testReport/api/json"):
            return Resp(status=404)
        if url.endswith("consoleText"):
            if "/job/pxc-package-testing/" in url:
                return Resp(text='fatal: [x]: FAILED! => {"stdout": "Telemetry Agent version is incorrect"}\n')
            if "/job/pxc-docker-tests/" in url:
                return Resp(text="52 passed\nFailed in branch Run Trivy Analyzer\nFinished: FAILURE\n")
            return Resp(text="pxc-package-testing #5157 completed with status UNSTABLE\nFinished: UNSTABLE\n")
        return Resp(status=404)


def test_collect_and_compute(monkeypatch):
    monkeypatch.setattr(jenkins.requests, "Session", FakeSession)
    cfg = yaml.safe_load(open(Path(__file__).resolve().parents[1] / "config.yaml"))
    cfg["masters"] = {"pxc": "https://pxc.cd.percona.com"}
    rec = jenkins.collect(cfg, "u", "t", now_ms=NOW)
    m = rec["masters"][0]
    assert {j["name"] for j in m["jobs"]} == {"pxc-package-testing-parallel", "test-pxc-binary-tarball",
                                             "test-pxc-binary-tarball-EOL", "pxc-docker-tests"}
    by_job = {}
    for b in m["builds"]:
        by_job.setdefault(b["job"], []).append(b["number"])
    assert by_job["pxc-docker-tests"] == [107, 104]          # #90 is older than 8 weeks
    assert by_job["pxc-package-testing"] == [5157]           # child of parallel-test #44 is ignored
    f = stats.compute(rec, cfg)
    assert f["scope"]["jobs_in_scope"] == 4
    assert f["builds"]["total"] == 3                         # children are not counted as runs
    assert f["builds"]["cells"] == 1 and f["builds"]["cells_red"] == 1
    assert f["causes"]["scan"] == 1 and f["causes"]["test"] == 1   # parallel #301 classed from its child
    assert f["by_type"]["docker"]["failed"] == 1 and f["by_type"]["package"]["unstable"] == 1


def test_rebuild_of_same_job_is_a_run_not_a_child():
    """ps80 ps-package-testing-molecule #980 'Rebuilds build #979' (Rebuild plugin = upstream cause)."""
    b = jenkins.normalize_build("ps-package-testing-molecule", {
        "number": 980, "result": "SUCCESS", "timestamp": NOW, "duration": 1, "url": "u", "building": False,
        "actions": [{"causes": [{"shortDescription": "Rebuilds build #979",
                                 "upstreamProject": "ps-package-testing-molecule", "upstreamBuild": 979}]}]})
    assert b["upstream"] is None
    cfg = yaml.safe_load(open(Path(__file__).resolve().parents[1] / "config.yaml"))
    rec = {"collected_at_ms": NOW + 1, "masters": [{"name": "ps80", "url": "u",
           "jobs": [{"name": "ps-package-testing-molecule", "product": "PS", "type": "package"}],
           "builds": [dict(b), dict(b, number=979, upstream={"job": "ps-package-testing-molecule", "number": 978})]}]}
    f = stats.compute(rec, cfg)
    assert f["builds"]["total"] == 2 and f["builds"]["cells"] == 0