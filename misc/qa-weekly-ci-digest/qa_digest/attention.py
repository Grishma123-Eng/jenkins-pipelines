"""'Needs attention' list: code ranks candidates, the model writes and orders them, code verifies.

An AI item survives only if:
  * it names a job that is one of the candidates,
  * its quote appears verbatim in the console evidence of the build it cites,
  * every number in its text appears in that job's computed facts or in the quote.
If no AI item survives (or no model is configured), rule-based items are used instead.
"""
from __future__ import annotations

import json
import os
import re

from .classify import best_quote

CAUSE_WORDS = {"test": "test failure", "scan": "Trivy/CVE scan only",
               "infra": "CI/infra problem", "other": "not classified"}

SYSTEM = """You write the "Needs attention" list of a weekly QA CI digest for a Percona QA team.
You get candidate jobs with facts computed by code and verbatim console evidence.
Pick the items a QA engineer should look at first (broken jobs, new test failures,
repeated CI problems). For each, write 1-2 plain sentences: what is wrong, and say whether
it looks like a product/test problem or a CI/infra problem. Use only numbers present in the
facts. Quote one evidence line verbatim (copy it exactly, you may cut it shorter).
Reply with JSON only: {"items":[{"job":..., "build": <number>, "text": ..., "quote": ...}]}"""


def candidates(facts: dict, limit: int = 15) -> list[dict]:
    out = []
    new_broken = set(facts["health"]["newly_broken"])
    for job, j in facts["jobs"].items():
        if j["week_builds"] == 0:
            continue  # the digest is about what ran this week
        red = j["week_failed"] + j["week_unstable"]
        causes = j.get("week_causes", {})
        score = 0.0
        score += 5 if job in new_broken else 4 if j["broken"] else 0
        score += min(j.get("new_failing_tests_count", 0), 5)
        score += 3 * red / j["week_builds"]
        score += 1 if causes.get("test") else 0          # real test failures first
        score -= 1 if red and causes.get("scan") == red else 0  # only the CVE scan failed
        score += 1.5 if causes.get("infra", 0) >= 2 else 0
        score += 1 if j["flaky"] else 0
        if score < 1:
            continue
        b = j.get("latest_red_this_week") or j["latest"]
        ev = facts["evidence"].get(f"{job}#{b['number']}", []) if b else []
        if not ev and b and b.get("red_cells"):  # parent of a matrix: use a red cell's log
            for c in b["red_cells"]:
                ev = facts["evidence"].get(f"{c['key']}#{c['number']}", [])
                if ev:
                    break
        out.append({"score": round(score, 2), "facts": j, "build": b and b["number"],
                    "job_url": j.get("url"), "build_url": b and b.get("url"), "evidence": ev})
    out.sort(key=lambda c: -c["score"])
    return out[:limit]


def ask_model(cands: list[dict], facts: dict, model: str, region: str | None,
              max_items: int, effort: str = "medium") -> list[dict]:
    import boto3  # only needed when AI is on

    client = boto3.client("bedrock-runtime", region_name=region)
    payload = {"window": facts["window"], "builds": facts["builds"], "causes": {
        k: v for k, v in facts["causes"].items() if k != "infra_builds"}, "candidates": cands,
        "max_items": max_items}
    kwargs = dict(
        modelId=model,
        system=[{"text": SYSTEM}],
        messages=[{"role": "user", "content": [{"text": json.dumps(payload, default=str)}]}],
        inferenceConfig={"maxTokens": 4000},
    )
    try:
        # effort is optional; if this Bedrock model/region rejects the field, retry without it
        resp = client.converse(**kwargs, additionalModelRequestFields={
            "output_config": {"effort": effort}}) if effort else client.converse(**kwargs)
    except client.exceptions.ValidationException:
        resp = client.converse(**kwargs)
    text = "".join(c.get("text", "") for c in resp["output"]["message"]["content"])
    m = re.search(r"\{.*\}", text, re.S)
    return json.loads(m.group(0)).get("items", []) if m else []


_num = re.compile(r"(?<![\w.#-])\d+(?:\.\d+)?(?![\w.])")


def verify(items: list[dict], cands: list[dict]) -> tuple[list[dict], list[dict]]:
    by_job = {c["facts"]["job"]: c for c in cands}
    ok, dropped = [], []
    for it in items:
        c = by_job.get(it.get("job"))
        reason = None
        if not c:
            reason = "job is not a candidate"
        else:
            quote = (it.get("quote") or "").strip()
            evidence = "\n".join(c["evidence"])
            if quote and quote not in evidence:
                reason = "quote not found in build evidence"
            else:
                allowed = set(_num.findall(json.dumps(c["facts"], default=str))) | set(_num.findall(quote))
                bad = [n for n in _num.findall(it.get("text", "")) if n not in allowed]
                if bad:
                    reason = f"numbers not in facts: {bad}"
        build = it.get("build") or (c and c["build"])
        links = {}
        if c:  # links come from Jenkins data, never from the model
            links = {"job_url": c.get("job_url"),
                     "build_url": c.get("build_url") if build == c["build"] else
                     (c.get("job_url") and f"{c['job_url']}{build}/")}
        (dropped if reason else ok).append(dict(it, **({"dropped": reason} if reason else {}),
                                               build=build, **links))
    return ok, dropped


def rule_based(cands: list[dict]) -> list[dict]:
    items = []
    for c in cands:
        j = c["facts"]
        parts = []
        if j["newly_broken"]:
            parts.append(f"Newly broken: the last {j['failing_streak']} builds failed")
        elif j["broken"]:
            parts.append(f"Failing streak of {j['failing_streak']} builds "
                         f"({j['history_red']} of the last {j['history_builds']} red)")
        red = j["week_failed"] + j["week_unstable"]
        parts.append(f"{red} of {j['week_builds']} runs failed or unstable this week")
        if j.get("new_failing_tests_count"):
            parts.append(f"{j['new_failing_tests_count']} tests failing that did not fail in the previous weeks")
        cz = j.get("week_causes", {})
        why = [f"{n} {CAUSE_WORDS[c]}{'s' if n > 1 and c in ('test', 'infra') else ''}"
               for c, n in cz.items() if n and c in CAUSE_WORDS]
        if why:
            parts.append("cause: " + ", ".join(why))
        if j["flaky"]:
            parts.append(f"flaky (result flips {int(j['flip_rate'] * 100)}% of the time)")
        quote = best_quote(c["evidence"])
        items.append({"job": j["job"], "build": c["build"], "text": ". ".join(parts) + ".", "quote": quote,
                      "job_url": c.get("job_url"), "build_url": c.get("build_url")})
    return items


def pick(facts: dict, cfg: dict) -> dict:
    cands = candidates(facts)
    model = os.environ.get("BEDROCK_MODEL", cfg["attention"].get("bedrock_model") or "")
    max_items = cfg["attention"]["max_items_total"]
    used_ai, dropped = False, []
    items: list[dict] = []
    if model and cands:
        try:
            raw = ask_model(cands, facts, model, os.environ.get("BEDROCK_REGION"), max_items,
                            os.environ.get("BEDROCK_EFFORT", "medium"))
            items, dropped = verify(raw, cands)
            used_ai = bool(items)
        except Exception as e:  # AI is optional; the digest still goes out
            dropped = [{"error": f"model call failed: {e}"}]
    if not items:
        items = rule_based(cands)
    return {"items": items[:max_items], "ai": used_ai, "model": model if used_ai else None,
            "dropped": dropped, "candidates": len(cands)}
