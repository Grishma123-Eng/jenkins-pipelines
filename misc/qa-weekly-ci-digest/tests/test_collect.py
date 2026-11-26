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


def build(n, result, days_ago, **extra):
    d = {"number": n, "result": result, "timestamp": NOW - days_ago * DAY, "duration": 600_000,
         "url": f"https://pxc.cd.percona.com/job/test-pxc-docker-5.7-EOL/{n}/", "building": False,
         "actions": [{"causes": [{"shortDescription": "Started by user grishma"}]}]}
    d.update(extra)
    return d


class FakeSession:
    auth = None

    def get(self, url, params=None, timeout=None, stream=False):
        if url.endswith("/api/json") and "allBuilds" in (params or {}).get("tree", ""):
            if "test-pxc-docker-5.7-EOL" in url:
                return Resp(json_data={"allBuilds": [build(107, "FAILURE", 1), build(104, "SUCCESS", 3),
                                                     build(90, "FAILURE", 80)]})
            return Resp(json_data={"allBuilds": []})
        if url.endswith("/api/json"):
            return Resp(json_data={"jobs": [
                {"_class": "x.WorkflowJob", "fullName": "test-pxc-docker-5.7-EOL", "url": "u", "color": "red"},
                {"_class": "x.WorkflowJob", "fullName": "pxc-docker-tests-grishma", "url": "u", "color": "blue"},
                {"_class": "com.cloudbees.hudson.plugins.folder.Folder", "fullName": "f", "jobs": [
                    {"_class": "x.WorkflowJob", "fullName": "f/hetzner-pxc8.0-docker-build", "url": "u"}]},
            ]})
        if url.endswith("testReport/api/json"):
            return Resp(status=404)
        if url.endswith("consoleText"):
            return Resp(text="52 passed\nFailed in branch Run Trivy Analyzer\nFinished: FAILURE\n")
        return Resp(status=404)


def test_collect_and_compute(monkeypatch):
    monkeypatch.setattr(jenkins.requests, "Session", FakeSession)
    cfg = yaml.safe_load(open(Path(__file__).resolve().parents[1] / "config.yaml"))
    cfg["masters"] = {"pxc": "https://pxc.cd.percona.com"}
    rec = jenkins.collect(cfg, "u", "t", now_ms=NOW)
    m = rec["masters"][0]
    assert [j["name"] for j in m["jobs"]] == ["test-pxc-docker-5.7-EOL"]   # personal copy + build job excluded
    assert [b["number"] for b in m["builds"]] == [107, 104]                  # #90 is older than 8 weeks
    assert "Trivy" in m["builds"][0]["log_tail"]
    f = stats.compute(rec, cfg)
    assert f["builds"]["total"] == 2 and f["builds"]["failed"] == 1
    assert f["causes"]["scan"] == 1
    assert f["by_product"]["PXC"]["runs"] == 2 and f["by_type"]["docker"]["failed"] == 1
