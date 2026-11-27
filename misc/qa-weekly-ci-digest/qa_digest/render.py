"""Render the digest as Slack Block Kit JSON, a plain-text fallback, and a full Markdown report."""
from __future__ import annotations

from datetime import datetime, timezone


def _day(ms: int) -> str:
    d = datetime.fromtimestamp(ms / 1000, tz=timezone.utc)
    return f"{d:%b} {d.day}"


def _plural(n: int, word: str) -> str:
    return f"{n} {word}{'' if n == 1 else 's'}"


def _slack_job(it: dict) -> str:
    """Job name linked to its Jenkins page, plus a link to the build the item is about.

    Slack link syntax is <url|text>. Links do not work inside `code`, so the name is plain.
    """
    job = f"<{it['job_url']}|{it['job']}>" if it.get("job_url") else f"`{it['job']}`"
    if it.get("build") and it.get("build_url"):
        job += f" (<{it['build_url']}|#{it['build']}>)"
    return job


def sections(facts: dict, att: dict, cfg: dict) -> list[tuple[str, str]]:
    b, cz = facts["builds"], facts["causes"]
    out = []
    line = (f"{b['total']}: {b['success']} passed, {b['failed']} failed, "
            f"{b['unstable']} unstable, {b['aborted']} aborted")
    if b["cells"]:
        line += f" (their {b['cells']} parallel child builds: {b['cells_red']} red)"
    out.append(("Builds", line))
    if cz["red"]:
        parts = [f"{cz['test']} test failures", f"{cz['scan']} Trivy/CVE scan only",
                 f"{cz['infra']} CI or infra"]
        if cz["other"]:
            parts.append(f"{cz['other']} not classified")
        why = f"of {cz['red']} failed or unstable: " + ", ".join(parts)
        if cz.get("aborted_on_ci"):
            why += f". Plus {cz['aborted_on_ci']} aborted on lost agents"
        out.append(("Why they failed", why))
    for title, field in (("By product", "by_product"), ("By type", "by_type")):
        if facts.get(field):
            out.append((title, "\n".join(
                f"{k}: {_plural(v['runs'], 'run')}, {v['failed'] + v['unstable']} failed or unstable"
                for k, v in facts[field].items())))
    if facts.get("new_test_failures") is not None:
        n = facts["new_test_failures"]
        out.append((f"Test failures not seen in {facts['window']['history_weeks']} weeks",
                    f"{n['total']} across {_plural(n['jobs'], 'job')}"))
    h = facts["health"]
    hl = f"{len(h['broken'])} broken, {len(h['flaky'])} flaky"
    if h["newly_broken"]:
        hl += f" ({len(h['newly_broken'])} newly broken)"
    out.append(("Broken and flaky jobs", hl))
    out.append(("Failed build hours", f"{facts['failed_build_hours']} h"))
    return out


def slack_blocks(facts: dict, att: dict, cfg: dict, report_url: str | None = None,
                 build_label: str | None = None) -> dict:
    w = facts["window"]
    head = f"{cfg['title']}, {_day(w['week_start_ms'])} to {_day(w['end_ms'])}"
    how = ("Numbers come from Jenkins by code. The attention items are AI-picked and checked "
           "against the build logs." if att["ai"] else
           "Numbers and attention items come from Jenkins by code.")
    blocks: list[dict] = [
        {"type": "header", "text": {"type": "plain_text", "text": head}},
        {"type": "context", "elements": [{"type": "mrkdwn", "text": how}]},
    ]
    for k, v in sections(facts, att, cfg):
        blocks.append({"type": "section", "text": {"type": "mrkdwn", "text": f"*{k}*\n{v}"}})

    n_show = cfg["attention"]["max_items_in_slack"]
    items = att["items"]
    if items:
        lines = [f"• {_slack_job(it)}: {it['text']}" for it in items[:n_show]]
        if len(items) > n_show:
            lines.append(f"_And {len(items) - n_show} more in the report._")
        blocks.append({"type": "section", "text": {"type": "mrkdwn",
                                                   "text": "*Needs attention*\n" + "\n".join(lines)}})
    if report_url:
        blocks.append({"type": "actions", "elements": [{"type": "button", "text": {
            "type": "plain_text", "text": "Open the report"}, "url": report_url}]})
    if build_label:
        blocks.append({"type": "context", "elements": [{"type": "mrkdwn", "text": build_label}]})
    return {"text": head, "blocks": blocks}


def slack_text(facts: dict, att: dict, cfg: dict) -> str:
    """Plain text version (what the preview below shows, and Slack's notification text)."""
    msg = slack_blocks(facts, att, cfg)
    out = []
    for blk in msg["blocks"]:
        if blk["type"] == "header":
            out.append(blk["text"]["text"])
        elif blk["type"] == "context":
            out.extend(e["text"] for e in blk["elements"])
        elif blk["type"] == "section":
            out.append(blk["text"]["text"])
    return "\n".join(out)


def markdown_report(facts: dict, att: dict, cfg: dict) -> str:
    w = facts["window"]
    md = [f"# {cfg['title']}, {_day(w['week_start_ms'])} to {_day(w['end_ms'])}", ""]
    md.append(f"Masters: {', '.join(facts['scope']['masters'])}. "
              f"{facts['scope']['jobs_in_scope']} jobs in scope, "
              f"{facts['scope']['jobs_built_this_week']} built this week.")
    md.append("")
    for k, v in sections(facts, att, cfg):
        md.append(f"- **{k}:** " + v.replace("\n", "; "))
    md += ["", "## Needs attention", ""]
    for i, it in enumerate(att["items"], 1):
        job = f"[`{it['job']}`]({it['job_url']})" if it.get("job_url") else f"`{it['job']}`"
        build = (f"[#{it['build']}]({it['build_url']})" if it.get("build_url")
                 else f"#{it.get('build')}")
        md.append(f"{i}. {job} {build}: {it['text']}")
        if it.get("quote"):
            md.append(f"   > `{it['quote']}`")
    if att.get("dropped"):
        md += ["", f"_{len(att['dropped'])} AI items were dropped by the checks._"]
    md += ["", "## Jobs that ran this week", "",
           "| Job | Product | Type | Runs | Failed | Unstable | Aborted | Cause of red runs | Fail streak | Flaky |",
           "|---|---|---|---|---|---|---|---|---|---|"]
    for j in sorted(facts["jobs"].values(), key=lambda x: (-(x["week_failed"] + x["week_unstable"]),
                                                          -x["week_builds"], x["job"])):
        if not j["week_builds"]:
            continue
        why = ", ".join(f"{k} {n}" for k, n in j.get("week_causes", {}).items())
        md.append(f"| {j['job']} | {j.get('product', '')} | {j.get('type', '')} | {j['week_builds']} | "
                  f"{j['week_failed']} | {j['week_unstable']} | {j['week_aborted']} | {why} | "
                  f"{j['failing_streak']} | {'yes' if j['flaky'] else ''} |")
    if facts["causes"]["infra_builds"]:
        md += ["", "## Builds classed as CI or infra problems", ""]
        md += [f"- {u}" for u in facts["causes"]["infra_builds"]]
    md += ["", "Causes: test = a functional test failed; scan = only the Trivy/CVE scan stage "
           "failed; infra = lost agent, git/network/cloud error; other = could not tell from the log."]
    return "\n".join(md) + "\n"