"""Every number in the digest is computed here, from the collected records. No AI involved.

Jobs are keyed "master/job" because the same job name can exist on several masters.
"""
from __future__ import annotations

import re
from collections import Counter, defaultdict

from .classify import classify, evidence_lines

RED = ("FAILURE", "UNSTABLE")
DAY = 86400_000
CAUSES = ("test", "scan", "infra", "other")


def key(master: str, job: str) -> str:
    return f"{master}/{job}"


def compute(records: dict, cfg: dict) -> dict:
    now = records["collected_at_ms"]
    week_start = now - cfg["window"]["week_days"] * DAY
    meta: dict[str, dict] = {}
    builds: list[dict] = []
    for m in records["masters"]:
        for j in m["jobs"]:
            meta[key(m["name"], j["name"])] = {"product": j.get("product", "?"),
                                               "type": j.get("type", "?")}
        for b in m["builds"]:
            up = (b.get("upstream") or {}).get("job", "")
            if up and any(re.search(p, up) for p in cfg.get("exclude_upstream", [])):
                continue  # started by a personal/test copy: not a real QA run
            if b.get("child_only") and key(m["name"], up or "") not in meta:
                continue  # a child build we only keep when one of our jobs started it
            builds.append(dict(b, master=m["name"], key=key(m["name"], b["job"])))

    # A "cell" is a build started by another in-scope job on the same master (matrix /
    # parallel children like pxc-package-testing under pxc-package-testing-parallel).
    for b in builds:
        up = b.get("upstream") or {}
        b["is_cell"] = bool(up) and key(b["master"], up.get("job", "")) in meta
        if b["result"] in RED + ("ABORTED",):
            b["cls"] = classify(b)

    cells_by_parent: dict[tuple, list[dict]] = defaultdict(list)
    for b in builds:
        if b["is_cell"]:
            cells_by_parent[(key(b["master"], b["upstream"]["job"]), b["upstream"]["number"])].append(b)
    # A parent that is red only because its cells were red takes its class from them.
    for b in builds:
        cells = cells_by_parent.get((b["key"], b["number"]))
        if not cells:
            continue
        red = [c for c in cells if c["result"] in RED]
        if b["result"] in RED and b.get("cls") in ("other", None) and red:
            classes = {c.get("cls") for c in red}
            b["cls"] = next((c for c in ("test", "scan", "infra") if c in classes), "other")
        b["cells"] = {"total": len(cells), "red": len(red),
                      "aborted": sum(c["result"] == "ABORTED" for c in cells)}
        if red:
            b["red_cells"] = [{"key": c["key"], "number": c["number"], "result": c["result"],
                               "class": c.get("cls")} for c in red][:30]

    top = [b for b in builds if not b["is_cell"]]
    week_top = [b for b in top if b["timestamp_ms"] >= week_start and b["result"]]
    week_cells = [b for b in builds if b["is_cell"] and b["timestamp_ms"] >= week_start and b["result"]]
    res = Counter(b["result"] for b in week_top)
    red_week = [b for b in week_top if b["result"] in RED]
    causes = Counter(b.get("cls") or "other" for b in red_week)

    # ---- per-job health (top-level builds, newest first)
    by_job: dict[str, list[dict]] = defaultdict(list)
    for b in sorted(top, key=lambda x: -x["timestamp_ms"]):
        if b["result"]:
            by_job[b["key"]].append(b)

    h = cfg["health"]
    jobs: dict[str, dict] = {}
    for k, bl in by_job.items():
        finished = [b for b in bl if b["result"] != "ABORTED"]
        streak = 0
        for b in finished:
            if b["result"] != "FAILURE":
                break
            streak += 1
        streak_start = finished[streak - 1]["timestamp_ms"] if streak else None
        states = [b["result"] == "SUCCESS" for b in reversed(finished)]
        flips = sum(a != c for a, c in zip(states, states[1:]))
        flip_rate = flips / (len(states) - 1) if len(states) > 1 else 0.0
        broken = streak >= h["broken_streak"]
        wk = [b for b in bl if b["timestamp_ms"] >= week_start]
        wk_red = [b for b in wk if b["result"] in RED]
        jobs[k] = {
            "job": k, **meta.get(k, {}),
            "history_builds": len(bl),
            "history_red": sum(b["result"] in RED for b in bl),
            "week_builds": len(wk),
            "week_failed": sum(b["result"] == "FAILURE" for b in wk),
            "week_unstable": sum(b["result"] == "UNSTABLE" for b in wk),
            "week_aborted": sum(b["result"] == "ABORTED" for b in wk),
            "week_causes": dict(Counter(b.get("cls") or "other" for b in wk_red)),
            "failing_streak": streak,
            "broken": broken,
            "newly_broken": broken and streak_start is not None and streak_start >= week_start,
            "flaky": (not broken and len(states) >= h["flaky_min_outcomes"]
                      and flip_rate >= h["flaky_flip_rate"]),
            "flip_rate": round(flip_rate, 2),
            "latest": _brief(bl[0]),
            # newest red build, preferring a real test failure over scan/infra for the quote
            "latest_red_this_week": _brief(next((b for b in wk_red if b.get("cls") == "test"),
                                                wk_red[0] if wk_red else None)),
        }

    # ---- test failures not seen earlier in the history window (junit names, per job)
    # needs junit names for every red build in the history window (the collector sets this)
    have_junit = bool(records.get("junit_history_complete"))
    new_tests_total = new_tests_jobs = 0
    if have_junit:
        hist_fail: dict[str, set] = defaultdict(set)
        week_fail: dict[str, set] = defaultdict(set)
        for b in builds:
            k = key(b["master"], b["upstream"]["job"]) if b["is_cell"] else b["key"]
            (week_fail if b["timestamp_ms"] >= week_start else hist_fail)[k].update(
                b.get("failed_tests") or [])
        for k, names in week_fail.items():
            fresh = sorted(n for n in names - hist_fail[k] if "CVE-" not in n and "GHSA-" not in n)
            if fresh:
                new_tests_total += len(fresh)
                new_tests_jobs += 1
                if k in jobs:
                    jobs[k]["new_failing_tests"] = fresh[:20]
                    jobs[k]["new_failing_tests_count"] = len(fresh)

    def breakdown(field: str) -> dict:
        out: dict[str, Counter] = defaultdict(Counter)
        for b in week_top:
            g = meta.get(b["key"], {}).get(field, "?")
            out[g]["runs"] += 1
            out[g]["failed"] += b["result"] == "FAILURE"
            out[g]["unstable"] += b["result"] == "UNSTABLE"
            out[g]["passed"] += b["result"] == "SUCCESS"
        return {k: dict(v) for k, v in sorted(out.items(), key=lambda kv: -kv[1]["runs"])}

    return {
        "window": {"week_start_ms": week_start, "end_ms": now,
                   "history_weeks": cfg["window"]["history_weeks"]},
        "scope": {"masters": [m["name"] for m in records["masters"]],
                  "jobs_in_scope": len(meta),
                  "jobs_built_this_week": len({b["key"] for b in week_top})},
        "builds": {"total": len(week_top), "success": res["SUCCESS"], "failed": res["FAILURE"],
                   "unstable": res["UNSTABLE"], "aborted": res["ABORTED"],
                   "cells": len(week_cells),
                   "cells_red": sum(c["result"] in RED for c in week_cells)},
        "causes": {"red": len(red_week), **{c: causes.get(c, 0) for c in CAUSES},
                   "aborted_on_ci": sum(b["result"] == "ABORTED" and b.get("cls") == "infra"
                                        for b in week_top),
                   "infra_builds": [b["url"] for b in red_week if b.get("cls") == "infra"]},
        "new_test_failures": ({"total": new_tests_total, "jobs": new_tests_jobs}
                              if have_junit else None),
        # only jobs that ran this week count: an idle job's old streak is not news
        "health": {f: sorted(k for k, v in jobs.items() if v[f] and v["week_builds"])
                   for f in ("broken", "newly_broken", "flaky")},
        "failed_build_hours": round(
            sum(b["duration_ms"] for b in week_top if b["result"] == "FAILURE") / 3600_000, 1),
        "by_product": breakdown("product"),
        "by_type": breakdown("type"),
        "jobs": jobs,
        "evidence": {f"{b['key']}#{b['number']}": evidence_lines(b.get("log_tail", ""))
                     for b in builds if b.get("log_tail")},
    }


def _brief(b: dict | None) -> dict | None:
    if not b:
        return None
    out = {"number": b["number"], "result": b["result"], "url": b.get("url"),
           "class": b.get("cls"), "params": b.get("params") or {}}
    if b.get("failed_tests"):
        out["failed_tests"] = b["failed_tests"][:10]
        out["failed_tests_count"] = len(b["failed_tests"])
    for k in ("cells", "red_cells"):
        if b.get(k):
            out[k] = b[k]
    return out
