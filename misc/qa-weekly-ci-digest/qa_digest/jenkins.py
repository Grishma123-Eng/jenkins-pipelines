"""Read-only Jenkins REST client. Collects the raw records the digest is computed from.

Nothing here writes to Jenkins. Output is a plain JSON-able dict so a run can be saved,
replayed and tested offline (see digest.py --records).
"""
from __future__ import annotations

import os
import re
import time
from typing import Any

import requests

BUILD_TREE = (
    "number,result,timestamp,duration,url,building,"
    "actions[causes[shortDescription,upstreamProject,upstreamBuild],"
    "parameters[name,value],failCount,totalCount,skipCount]"
)
LOG_TAIL_BYTES = 120_000  # enough for the failure summary at the end of a console


class Jenkins:
    def __init__(self, url: str, user: str | None, token: str | None, timeout: int = 60):
        self.url = url.rstrip("/")
        self.s = requests.Session()
        if user and token:
            self.s.auth = (user, token)
        self.timeout = timeout

    def _get(self, path: str, **params: Any) -> requests.Response:
        for attempt in range(3):
            r = self.s.get(f"{self.url}{path}", params=params, timeout=self.timeout)
            if r.status_code < 500:
                return r
            time.sleep(2 * (attempt + 1))
        r.raise_for_status()
        return r

    @staticmethod
    def job_path(fullname: str) -> str:
        return "".join(f"/job/{requests.utils.quote(p, safe='')}" for p in fullname.split("/"))

    def list_jobs(self) -> list[dict]:
        """Flat list of buildable jobs (folders are walked)."""
        tree = "jobs[fullName,name,url,color,buildable,_class,jobs[fullName,name,url,color,buildable,_class,jobs[fullName,name,url,color,buildable,_class]]]"
        data = self._get("/api/json", tree=tree).json()
        out: list[dict] = []

        def walk(nodes: list[dict]) -> None:
            for n in nodes or []:
                if "Folder" in n.get("_class", "") or n.get("jobs"):
                    walk(n.get("jobs", []))
                else:
                    out.append({"name": n["fullName"], "url": n["url"], "color": n.get("color"),
                                "disabled": n.get("color") == "disabled"})

        walk(data.get("jobs", []))
        return out

    def builds(self, fullname: str, limit: int) -> list[dict]:
        r = self._get(f"{self.job_path(fullname)}/api/json",
                      tree=f"allBuilds[{BUILD_TREE}]{{0,{limit}}}")
        if r.status_code == 404:
            return []
        r.raise_for_status()
        return [normalize_build(fullname, b) for b in r.json().get("allBuilds", [])]

    def log_tail(self, build_url: str, nbytes: int = LOG_TAIL_BYTES,
                 max_scan: int = 200_000_000, max_hits: int = 300) -> str:
        """Error lines from the WHOLE console plus its last nbytes.

        Big logs (molecule) put the real error far before the end, so the console is streamed
        once: every error-looking line is kept (up to max_hits, de-duplicated), and the tail is
        kept for the final status. Nothing large is held in memory.
        """
        from .classify import ERROR_LINE  # local import keeps jenkins.py usable on its own

        tail = b""
        hits: list[str] = []
        seen_hits: set[str] = set()
        rest = b""
        scanned = 0
        with self.s.get(self.url + _path_of(build_url) + "consoleText", stream=True,
                        timeout=self.timeout) as r:
            if not r.ok:
                return ""
            for chunk in r.iter_content(chunk_size=1 << 16):
                scanned += len(chunk)
                tail = (tail + chunk)[-nbytes:]
                lines = (rest + chunk).split(b"\n")
                rest = lines.pop()  # last piece may be an unfinished line
                if len(hits) < max_hits:
                    for raw in lines:
                        line = raw.decode("utf-8", "replace").strip()[:400]
                        if line and line not in seen_hits and ERROR_LINE.search(line) \
                                and "MaskedException" not in line and not line.startswith("at "):
                            seen_hits.add(line)
                            hits.append(line)
                            if len(hits) >= max_hits:
                                break
                if scanned > max_scan:
                    break
        return ("\n".join(hits) + "\n----- end of log -----\n"
                + tail.decode("utf-8", "replace"))

    def failed_tests(self, build_url: str, cap: int = 500) -> list[str] | None:
        r = self._get(_path_of(build_url) + "testReport/api/json",
                      tree="suites[cases[className,name,status]]")
        if r.status_code == 404:
            return None  # no junit report
        if not r.ok:
            return None
        names = []
        for s in r.json().get("suites", []):
            for c in s.get("cases", []):
                if c.get("status") in ("FAILED", "REGRESSION"):
                    names.append(f"{c.get('className', '')}::{c.get('name', '')}")
                    if len(names) >= cap:
                        return names
        return names


def _path_of(url: str) -> str:
    """'https://host/job/x/5/' -> '/job/x/5/' (Jenkins may report a different root URL)."""
    m = re.match(r"^https?://[^/]+(/.*)$", url)
    path = m.group(1) if m else url
    return path if path.endswith("/") else path + "/"


def normalize_build(job: str, b: dict) -> dict:
    causes, params, upstream, tests = [], {}, None, None
    for a in b.get("actions") or []:
        if not a:
            continue
        for c in a.get("causes") or []:
            causes.append(c.get("shortDescription", ""))
            up_job = c.get("upstreamProject")
            # a Rebuild/Replay of the same job is reported as an "upstream" cause too:
            # that is a normal run of this job, not a child build
            if up_job and up_job != job and upstream is None:
                upstream = {"job": up_job, "number": c.get("upstreamBuild")}
        for p in a.get("parameters") or []:
            if isinstance(p.get("value"), (str, int, float, bool)):
                params[p["name"]] = p["value"]
        if "failCount" in a:
            tests = {"fail": a.get("failCount", 0), "total": a.get("totalCount", 0),
                     "skip": a.get("skipCount", 0)}
    return {
        "job": job,
        "number": b["number"],
        "result": None if b.get("building") else b.get("result"),
        "timestamp_ms": b.get("timestamp", 0),
        "duration_ms": b.get("duration", 0),
        "url": b.get("url", ""),
        "causes": causes,
        "upstream": upstream,
        "params": params,
        "test_counts": tests,
    }


def job_list(master: str, cfg: dict) -> list[dict]:
    """The report's jobs on one master, from config.yaml (exact names)."""
    return [dict(j) for j in (cfg.get("jobs") or {}).get(master, [])]


def match_group(master: str, name: str, cfg: dict) -> dict | None:
    """product/type of a job if it is in the report, else None."""
    return next((j for j in job_list(master, cfg) if j["name"] == name), None)


def collect(cfg: dict, user: str | None, token: str | None, now_ms: int | None = None) -> dict:
    """Collect everything the digest needs. Logs and test reports only for non-green builds."""
    now_ms = now_ms or int(time.time() * 1000)
    hist_start = now_ms - cfg["window"]["history_weeks"] * 7 * 86400_000
    week_start = now_ms - cfg["window"]["week_days"] * 86400_000
    records: dict = {"collected_at_ms": now_ms, "junit_history_complete": True, "masters": []}

    def details(jk: Jenkins, b: dict) -> dict:
        if b["result"] in ("FAILURE", "UNSTABLE", "ABORTED"):
            # test names for the whole history (needed for "not seen in 8 weeks")
            b["failed_tests"] = jk.failed_tests(b["url"])
            # console tail only for this week's builds (evidence for causes)
            if b["timestamp_ms"] >= week_start:
                b["log_tail"] = jk.log_tail(b["url"])
        return b

    for master, url in cfg["masters"].items():
        # per-master token: JENKINS_API_USER_PXC / JENKINS_API_TOKEN_PXC ...
        m_user = os.environ.get(f"JENKINS_API_USER_{master.upper()}", user)
        m_token = os.environ.get(f"JENKINS_API_TOKEN_{master.upper()}", token)
        jk = Jenkins(url, m_user, m_token)
        jobs = [dict(j, url=f"{url}{Jenkins.job_path(j['name'])}/") for j in job_list(master, cfg)]
        names = {j["name"] for j in jobs}
        builds: list[dict] = []
        for j in jobs:
            for b in jk.builds(j["name"], cfg["window"]["builds_per_job"]):
                if b["timestamp_ms"] >= hist_start:
                    builds.append(details(jk, b))
        # child builds, only those started by a job in the report
        for child in (cfg.get("children") or {}).get(master, []):
            for b in jk.builds(child, cfg["window"]["builds_per_job"]):
                if b["timestamp_ms"] >= hist_start and (b.get("upstream") or {}).get("job") in names:
                    b["child_only"] = True
                    builds.append(details(jk, b))
        records["masters"].append({"name": master, "url": url, "jobs": jobs, "builds": builds})
    return records