#!/usr/bin/env python3
"""QA weekly CI digest.

  collect -> compute -> pick attention -> render.  Never posts to Slack itself:
  it writes out/slack.json, and the Jenkinsfile posts it only when SLACK_CHANNEL is set.

Usage:
  python digest.py                         # collect from Jenkins (JENKINS_API_USER/TOKEN)
  python digest.py --records rec.json      # replay saved records (offline, for testing)
  python digest.py --no-ai                 # skip the model, rule-based attention items
"""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import sys

import yaml

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from qa_digest import attention, render, stats  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", default=str(pathlib.Path(__file__).with_name("config.yaml")))
    ap.add_argument("--records", help="saved records JSON instead of calling Jenkins")
    ap.add_argument("--out", default="out")
    ap.add_argument("--no-ai", action="store_true")
    a = ap.parse_args()

    cfg = yaml.safe_load(open(a.config))
    out = pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)

    if a.records:
        records = json.load(open(a.records))
    else:
        from qa_digest.jenkins import collect
        records = collect(cfg, os.environ.get("JENKINS_API_USER"), os.environ.get("JENKINS_API_TOKEN"))
        (out / "records.json").write_text(json.dumps(records))

    facts = stats.compute(records, cfg)
    if a.no_ai:
        cfg["attention"]["bedrock_model"] = ""
        os.environ.pop("BEDROCK_MODEL", None)
    att = attention.pick(facts, cfg)

    build_url = os.environ.get("BUILD_URL")
    report_url = f"{build_url}artifact/misc/qa-weekly-ci-digest/out/report.md" if build_url else None
    label = (f"<{build_url}|{os.environ.get('JOB_NAME')} #{os.environ.get('BUILD_NUMBER')}>"
             if build_url else None)

    (out / "facts.json").write_text(json.dumps(facts, indent=1, default=str))
    (out / "attention.json").write_text(json.dumps(att, indent=1, default=str))
    (out / "slack.json").write_text(json.dumps(render.slack_blocks(facts, att, cfg, report_url, label)))
    (out / "slack.txt").write_text(render.slack_text(facts, att, cfg))
    (out / "report.md").write_text(render.markdown_report(facts, att, cfg))
    print((out / "slack.txt").read_text())
    print(f"\nattention: {len(att['items'])} items, ai={att['ai']}, dropped={len(att['dropped'])}",
          file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
