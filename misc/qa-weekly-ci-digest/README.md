# QA weekly CI digest

A weekly Jenkins job that summarises the QA test jobs (package testing, tarball, docker) on
**ps80, pxc, pxb and ps57** for the past 7 days and, when you switch it on, posts the summary to
a Slack channel. It follows the design of the ps80 dev digest in #ps-standup
(`examples/bedrock-boto3-jobs-report`), but covers only QA jobs and lives in its own files.

## What the Slack message shows

```
QA weekly CI digest, Nov 12 to Nov 19
Builds               39: 15 passed, 16 failed, 2 unstable, 6 aborted (their 119 parallel child builds: 56 red)
Why they failed      of 18 failed or unstable: 9 test failures, 6 Trivy/CVE scan only, 3 CI or infra
By product           PS: 21 runs, 12 failed or unstable / PXC ... / PXB ... / PS 5.7 ...
By type              docker / package / tarball
Broken and flaky     5 broken, 3 flaky (2 newly broken)
Failed build hours   3.6 h
Needs attention      top 3 jobs, with the real error line from the log ("And N more in the report")
```

`out/report.md` (archived with each build, linked from the message) has the full list and a
table of every job that ran.

### How a red build is classed

| Cause | Meaning | Example |
|---|---|---|
| test | a functional test or check failed | `AssertionError: assert '8.4.10' == '8.4.11'` |
| scan | only the Trivy/CVE scan stage failed, the tests passed | `Failed in branch Run Trivy Analyzer` after `66 passed` |
| infra | lost agent, git/network/cloud problem, molecule could not create instances | `Cannot run program "git"`, `instance_config.yml ... file not found` |
| other | could not tell from the log | |

Builds aborted by a person are not counted as failures. Builds aborted because an agent was lost
are reported separately ("aborted on lost agents").

Parallel child builds (e.g. `pxc-package-testing` runs started by `pxc-package-testing-parallel`)
roll up into their parent. Children of excluded jobs (e.g. `...-parallel-test`) are ignored.

## Scope

All in `config.yaml`. A job is in when it matches a group's pattern on that master and does not
match `exclude` (personal copies such as `-grishma`, `-tomislav`, `-sveta`, `-yash`, and
`-test` / `-testing` scratch jobs). Each group gives the job its **product** and **type**.
To add or remove a job, edit the patterns there; no code changes are needed.

## Files (in Percona-Lab/jenkins-pipelines)

```
misc/
├── qa-weekly-ci-digest.groovy   pipeline: build digest, post only if SLACK_CHANNEL set and DRY_RUN off
├── qa-weekly-ci-digest.yml      job definition (weekly trigger, Mondays ~12:45 IST)
└── qa-weekly-ci-digest/
    ├── config.yaml              masters, job groups (product/type), excludes, thresholds
    ├── digest.py                entry point
    ├── requirements.txt
    ├── qa_digest/               jenkins.py, classify.py, stats.py, attention.py, render.py
    └── tests/                   classifier tests from real log lines + fake-Jenkins collector test
```

## Setting it up

1. Create a Jenkins **username + API token** credential `qa-digest-jenkins-readonly` on the
   master that runs the job (ps80), for a user that can read the QA jobs on ps80, pxc, pxb and
   ps57. If each master needs its own token, also set `JENKINS_API_TOKEN_PXC`,
   `JENKINS_API_TOKEN_PXB`, `JENKINS_API_TOKEN_PS57` (and `_USER_`) in the job's environment.
2. Test from your branch first: New Item → Pipeline, "Pipeline script from SCM", your repo and
   branch, script path `misc/qa-weekly-ci-digest.groovy`. Run with the defaults
   (`SLACK_CHANNEL` empty, `DRY_RUN` ticked) and read `out/slack.txt` and `out/report.md` in the
   build artifacts.
3. After the pull request is merged, the job `qa-weekly-ci-digest` is created from
   `misc/qa-weekly-ci-digest.yml` (it points at `master`).
4. Turn on Slack: set `SLACK_CHANNEL`, untick `DRY_RUN`. The Slack app used by `slackSend` must
   be a member of that channel.

## Optional: AI-written "Needs attention"

Leave `BEDROCK_MODEL` empty for the code-only version. To use AI like the ps80 digest, set
`BEDROCK_MODEL` to a Bedrock model ID or inference profile and give the agent AWS credentials
for Bedrock. The model only chooses and words the items: every item must name a real candidate
job, quote a line that really appears in that build's log, and use only numbers that code
computed. Items that fail these checks are dropped, and if none pass, the code-only items are
used.

## Running locally

```
pip install -r requirements.txt
export JENKINS_API_USER=... JENKINS_API_TOKEN=...
python digest.py --out out             # collect + build, never posts
python digest.py --records out/records.json --out out2   # replay a saved run offline
python -m pytest tests/
```
