#!/usr/bin/env python3
"""Pull security findings from every gate into the dashboard.

Sources, and why each is pulled rather than pushed:

  Checkov / Trivy / OPA   GitHub Actions artifacts, fetched with `gh`.
                          A hosted runner cannot reach a dashboard on
                          this laptop, so CI publishes artifacts and this
                          script collects them.
  Kyverno                 Read live from the cluster. Kyverno's reports
                          controller already generates PolicyReport
                          objects in the background; nothing was
                          collecting them until now.
  Remediations            JSON objects the Lambda writes to the audit
                          bucket. Phase 5 established that a Lambda in
                          LocalStack cannot reliably reach the host, so
                          it writes to S3 and this script reads from it.

Nothing here pushes into the dashboard from inside CI or the cluster —
every path is a pull, so no component needs to know the dashboard exists.

Usage:
    scripts/sync-findings.py                 # everything
    scripts/sync-findings.py --only kyverno  # one source
    scripts/sync-findings.py --include-system-namespaces
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from pathlib import Path

DASHBOARD = os.environ.get("DASHBOARD_URL", "http://localhost:8090")
LOCALSTACK = os.environ.get("AWS_ENDPOINT_URL_LOCAL", "http://localhost:4566")
AUDIT_BUCKET = os.environ.get("AUDIT_BUCKET", "northbound-audit-logs")

# Kyverno's ClusterPolicies match Pods cluster-wide, so its background
# scans cover Kubernetes' own control plane too. Those findings describe
# infrastructure this project doesn't own or control, and there are far
# more of them than of our own workloads — left in, they would dominate
# the dashboard and make the score meaningless. Override with
# --include-system-namespaces if you specifically want them.
SYSTEM_NAMESPACES = {"kube-system", "kube-public", "kube-node-lease", "kyverno", "local-path-storage"}


def run(cmd: list[str], check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, check=check)


def post(path: str, payload: dict) -> dict:
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        f"{DASHBOARD}{path}", data=body, headers={"Content-Type": "application/json"}, method="POST"
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.URLError as e:
        sys.exit(f"ERROR: cannot reach dashboard at {DASHBOARD} ({e}). Is it running?")


def submit_scan(source: str, passed: int, failed: int, run_ref: str, findings: list[dict]) -> None:
    result = post(
        "/api/scans",
        {
            "source": source,
            "passed": passed,
            "failed": failed,
            "runRef": run_ref,
            "ranAt": None,
            "findings": findings,
        },
    )
    status = result.get("status")
    note = ""
    if result.get("sweepSkipped"):
        # Worth shouting about: the scanner produced nothing, so the
        # dashboard refused to resolve anything. Silence here would look
        # identical to a clean run.
        note = "  <-- INCONCLUSIVE, existing findings left untouched"
    print(
        f"  {source:<8} {passed:>4} passed  {failed:>4} failed  "
        f"{len(findings):>3} findings ingested  {result.get('findingsResolved', 0):>3} resolved  [{status}]{note}"
    )


# --------------------------------------------------------------------------
# GitHub Actions artifacts
# --------------------------------------------------------------------------


def latest_run_id(workflow: str) -> str | None:
    """Most recent run of a workflow, regardless of conclusion.

    Deliberately not filtered to successful runs: these gates fail closed
    by design, so the runs carrying the findings we want are exactly the
    failing ones. Artifacts upload with `if: always()`.
    """
    proc = run(
        ["gh", "run", "list", "--workflow", workflow, "--limit", "1", "--json", "databaseId,conclusion"],
        check=False,
    )
    if proc.returncode != 0:
        print(f"  ! could not list runs for {workflow}: {proc.stderr.strip()}")
        return None
    runs = json.loads(proc.stdout or "[]")
    return str(runs[0]["databaseId"]) if runs else None


def download_artifact(run_id: str, name: str, dest: Path) -> Path | None:
    proc = run(["gh", "run", "download", run_id, "-n", name, "-D", str(dest)], check=False)
    if proc.returncode != 0:
        print(f"  ! artifact '{name}' not available on run {run_id}: {proc.stderr.strip()}")
        return None
    files = list(dest.glob("*"))
    return files[0] if files else None


def sync_checkov(tmp: Path) -> None:
    print("Checkov (GitHub Actions artifact)")
    run_id = latest_run_id("security-scan.yml")
    if not run_id:
        return
    path = download_artifact(run_id, "checkov-results", tmp / "checkov")
    if not path:
        return

    data = json.loads(path.read_text())
    # Checkov emits a list when more than one check type ran, a bare
    # object when only one did.
    blocks = data if isinstance(data, list) else [data]

    # A SARIF file is also valid JSON, so a workflow still emitting SARIF
    # parses fine here and yields nothing — which would be submitted as a
    # 0/0 scan and be indistinguishable from a scanner that genuinely
    # found nothing. Refuse to submit rather than report a shape we
    # didn't understand.
    if not any("summary" in block for block in blocks):
        print(
            f"  ! {path.name} has no 'summary' block — this looks like SARIF, not Checkov JSON.\n"
            f"    The workflow needs output_format: cli,json. Skipping rather than "
            f"submitting an empty scan."
        )
        return

    passed = failed = 0
    findings = []
    for block in blocks:
        summary = block.get("summary", {})
        passed += summary.get("passed", 0)
        failed += summary.get("failed", 0)
        for check in block.get("results", {}).get("failed_checks", []):
            findings.append(
                {
                    "resource": check.get("resource") or check.get("file_path"),
                    "ruleId": check.get("check_id"),
                    "message": check.get("check_name"),
                    "severity": check.get("severity") or "UNKNOWN",
                }
            )
    submit_scan("CHECKOV", passed, failed, f"gh-{run_id}", findings)


def sync_trivy(tmp: Path) -> None:
    print("Trivy (GitHub Actions artifact)")
    run_id = latest_run_id("security-scan.yml")
    if not run_id:
        return
    path = download_artifact(run_id, "trivy-results", tmp / "trivy")
    if not path:
        return

    data = json.loads(path.read_text())
    if "runs" not in data:
        print(f"  ! {path.name} has no 'runs' key — not SARIF. Skipping rather than submitting an empty scan.")
        return

    findings = []
    for sarif_run in data.get("runs", []):
        for result in sarif_run.get("results", []):
            message = result.get("message", {}).get("text", "")
            # Trivy's SARIF message embeds "Package: <name>"; that is the
            # most useful resource identifier available here.
            resource = "unknown"
            for line in message.splitlines():
                if line.startswith("Package:"):
                    resource = line.split(":", 1)[1].strip()
                    break
            findings.append(
                {
                    "resource": resource,
                    "ruleId": result.get("ruleId"),
                    "message": message.splitlines()[0] if message else "",
                    "severity": (result.get("properties", {}) or {}).get("security-severity", "UNKNOWN"),
                }
            )
    # passed is 0 on purpose: Trivy reports vulnerabilities found, not
    # checks passed. The dashboard keeps it out of the compliance score
    # for exactly this reason.
    submit_scan("TRIVY", 0, len(findings), f"gh-{run_id}", findings)


def sync_opa(tmp: Path) -> None:
    print("OPA / conftest (GitHub Actions artifact)")
    run_id = latest_run_id("admission-gate.yml")
    if not run_id:
        return
    path = download_artifact(run_id, "opa-results", tmp / "opa")
    if not path:
        return

    data = json.loads(path.read_text())
    if not isinstance(data, list) or not all("successes" in entry for entry in data):
        print(
            f"  ! {path.name} is not conftest JSON output (expected a list of objects with "
            f"'successes'). Skipping rather than submitting an empty scan."
        )
        return

    passed = failed = 0
    findings = []
    for entry in data:
        passed += entry.get("successes", 0)
        for failure in entry.get("failures", []):
            failed += 1
            meta = failure.get("metadata", {}) or {}
            findings.append(
                {
                    "resource": meta.get("resource", "unknown"),
                    # The Rego policies emit ruleId as structured metadata
                    # precisely so it doesn't have to be parsed out of the
                    # message text, which would make fingerprints break
                    # whenever a message was reworded.
                    "ruleId": meta.get("ruleId", "OPA_UNKNOWN"),
                    "message": failure.get("msg", ""),
                    "severity": meta.get("severity", "UNKNOWN"),
                }
            )
    submit_scan("OPA", passed, failed, f"gh-{run_id}", findings)


# --------------------------------------------------------------------------
# Kyverno (live cluster)
# --------------------------------------------------------------------------


def sync_kyverno(include_system: bool) -> None:
    print("Kyverno (live cluster)")
    proc = run(["kubectl", "get", "policyreport,clusterpolicyreport", "-A", "-o", "json"], check=False)
    if proc.returncode != 0:
        print(f"  ! cannot read policy reports: {proc.stderr.strip()}")
        return

    items = json.loads(proc.stdout or '{"items": []}').get("items", [])
    passed = failed = 0
    findings = []
    skipped_namespaces = set()

    for item in items:
        namespace = item.get("metadata", {}).get("namespace")
        if namespace in SYSTEM_NAMESPACES and not include_system:
            skipped_namespaces.add(namespace)
            continue

        summary = item.get("summary", {})
        passed += summary.get("pass", 0)
        failed += summary.get("fail", 0)

        scope = item.get("scope", {}) or {}
        scope_name = f"{scope.get('kind', '?')}/{scope.get('name', '?')}"

        for result in item.get("results", []):
            if result.get("result") != "fail":
                continue
            findings.append(
                {
                    "resource": f"{namespace}/{scope_name}" if namespace else scope_name,
                    "ruleId": result.get("policy", "unknown"),
                    "message": result.get("message", ""),
                    "severity": result.get("severity") or "MEDIUM",
                }
            )

    if skipped_namespaces:
        print(
            f"  (skipped system namespaces: {', '.join(sorted(skipped_namespaces))} "
            f"— pass --include-system-namespaces to include them)"
        )
    submit_scan("KYVERNO", passed, failed, "cluster", findings)


# --------------------------------------------------------------------------
# Remediation records (LocalStack S3)
# --------------------------------------------------------------------------


def sync_remediations() -> None:
    print("Remediations (LocalStack S3 audit bucket)")
    env = {**os.environ, "AWS_ACCESS_KEY_ID": os.environ.get("AWS_ACCESS_KEY_ID", "test"),
           "AWS_SECRET_ACCESS_KEY": os.environ.get("AWS_SECRET_ACCESS_KEY", "test"),
           "AWS_DEFAULT_REGION": os.environ.get("AWS_DEFAULT_REGION", "us-east-1")}

    listing = subprocess.run(
        ["aws", "--endpoint-url", LOCALSTACK, "s3api", "list-objects-v2",
         "--bucket", AUDIT_BUCKET, "--prefix", "remediations/", "--query", "Contents[].Key", "--output", "json"],
        capture_output=True, text=True, env=env, check=False,
    )
    if listing.returncode != 0:
        print(f"  ! cannot list audit bucket: {listing.stderr.strip()}")
        return

    keys = json.loads(listing.stdout or "null") or []
    stored = skipped = 0
    for key in keys:
        obj = subprocess.run(
            ["aws", "--endpoint-url", LOCALSTACK, "s3", "cp", f"s3://{AUDIT_BUCKET}/{key}", "-"],
            capture_output=True, text=True, env=env, check=False,
        )
        if obj.returncode != 0:
            print(f"  ! cannot read {key}")
            continue
        record = json.loads(obj.stdout)
        result = post(
            "/api/remediations",
            {
                # The object key is the idempotency key — re-running this
                # script must not count one fix twice.
                "sourceKey": key,
                "resource": record.get("resource"),
                "rule": record.get("rule"),
                "triggeredAt": record.get("triggeredAt"),
                "remediatedAt": record.get("remediatedAt"),
                "details": record.get("details"),
            },
        )
        if result.get("stored"):
            stored += 1
        else:
            skipped += 1
    print(f"  {len(keys)} records found, {stored} new, {skipped} already ingested")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--only", choices=["checkov", "trivy", "opa", "kyverno", "remediations"])
    parser.add_argument(
        "--include-system-namespaces",
        action="store_true",
        help="include Kyverno findings about kube-system and other cluster-owned namespaces",
    )
    args = parser.parse_args()

    print(f"Syncing into {DASHBOARD}\n")
    with tempfile.TemporaryDirectory() as tmpdir:
        tmp = Path(tmpdir)
        if args.only in (None, "checkov"):
            sync_checkov(tmp)
        if args.only in (None, "trivy"):
            sync_trivy(tmp)
        if args.only in (None, "opa"):
            sync_opa(tmp)
        if args.only in (None, "kyverno"):
            sync_kyverno(args.include_system_namespaces)
        if args.only in (None, "remediations"):
            sync_remediations()
    print(f"\nDone. Dashboard: {DASHBOARD}")


if __name__ == "__main__":
    main()
