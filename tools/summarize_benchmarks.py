"""Summarize one benchmark run, or compare runs that share one measurement protocol, from raw CSV samples.

Pass report.json files from benchmark-results directories. With one report, prints its per-case
distributions. With several, for example the five Minecraft versions or a baseline and a candidate,
prints one column per report and the mean change relative to the first report. Reports must come from
the same machine and protocol; version, Java and driver differences are listed, not rejected.
"""
import argparse
import csv
import json
from pathlib import Path
from statistics import mean

PROTOCOL = ["backend", "width", "height", "guiScale", "vsync", "frameLimit", "warmupFrames", "pipelineWarmupFrames",
            "gpuDrainFrames", "measuredFrames", "repeats", "allocations", "windowMode", "focusSource", "world", "control"]
MACHINE = ["os", "cpu", "logicalProcessors"]
NOTED = ["minecraft", "loader", "java", "osVersion", "resourcePacks", "windowIsolation"]


def distribution(values):
    ordered = sorted(values)
    if not ordered:
        return None
    def percentile(p):
        return ordered[max(0, (len(ordered) * p + 99) // 100 - 1)]
    return {"samples": len(ordered), "p50": percentile(50), "p95": percentile(95),
            "p99": percentile(99), "mean": mean(ordered)}


def load_run(path):
    report = json.loads(path.read_text(encoding="utf-8"))
    environment = report["environment"]
    if environment.get("control"):
        raise ValueError(f"A control run has no samples: {path}")
    groups = {}
    for case in report["results"]:
        csv_path = path.parent / f'{case["repeat"]}-{case["name"]}.csv'
        with csv_path.open(encoding="utf-8", newline="") as stream:
            rows = list(csv.DictReader(stream))
        if len(rows) != environment["measuredFrames"]:
            raise ValueError(f"Incomplete sample set: {csv_path}")
        ids = [int(row["frame_id"]) for row in rows]
        if ids != list(range(ids[0], ids[0] + len(ids))):
            raise ValueError(f"Non-contiguous frame IDs: {csv_path}")
        for row in rows:
            if int(row["missing_gpu"]):
                raise ValueError(f"Missing GPU results: {csv_path}")
            top_level = sum(int(value) for name, value in row.items() if name.startswith("cpu_") and name.endswith("_ns"))
            if top_level > int(row["total_cpu_wall_ns"]):
                raise ValueError(f"Overlapping top-level CPU spans: {csv_path}")
        groups.setdefault(case["name"], []).extend(rows)
    if len(report["results"]) != len(groups) * environment["repeats"]:
        raise ValueError(f"Incomplete repetitions: {path}")
    metrics = {}
    for name, rows in groups.items():
        columns = [key for key in rows[0] if key.endswith("_ns") and key != "start_relative_ns"]
        columns += ["render_bytes", "compose_bytes", "compose_calls"]
        values = {key: distribution([int(row[key]) for row in rows if row[key] != ""]) for key in columns}
        # Backends without GPU timers leave every GPU column empty.
        gpu_keys = [key for key in rows[0] if key.startswith("gpu_") and key.endswith("_ns")]
        if gpu_keys and all(row[key] != "" for row in rows for key in gpu_keys):
            values["gpu_stage_sum_ns"] = distribution([sum(int(row[key]) for key in gpu_keys) for row in rows])
        values["redraws"] = sum(row["rendered"] == "true" for row in rows)
        values["max_cached_items"] = max(int(row["cached_items"]) for row in rows)
        values["pending_item_frames"] = sum(int(row["pending_items"]) > 0 for row in rows)
        metrics[name] = values
    return environment, metrics


def title(environment, path):
    return f'{environment.get("minecraft", "?")} {environment.get("label", path.parent.parent.name)}'


def device(environment):
    value = environment.get("device") or {}
    return value.get("vendor"), value.get("gpu")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("reports", nargs="+", type=Path, help="benchmark-results/report.json files")
    parser.add_argument("--json", type=Path, help="Optional output with pooled raw-frame distributions")
    args = parser.parse_args()
    runs = [(path, *load_run(path)) for path in args.reports]
    first_path, first_environment, first_metrics = runs[0]
    for path, environment, metrics in runs[1:]:
        changed = [key for key in PROTOCOL + MACHINE if environment.get(key) != first_environment.get(key)]
        if device(environment) != device(first_environment):
            changed.append("device")
        if changed:
            raise ValueError(f"{path} does not match the measurement conditions of {first_path}: {changed}")
        if list(metrics) != list(first_metrics):
            raise ValueError(f"{path} does not measure the same cases as {first_path}")
    for key in NOTED + ["driver"]:
        values = {str((env.get("device") or {}).get(key) if key == "driver" else env.get(key)) for _, env, _ in runs}
        if len(values) > 1:
            print(f"Note: {key} differs between reports: {sorted(values)}")
    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        output = {"runs": [{"report": str(path), "environment": env, "metrics": metrics} for path, env, metrics in runs]}
        args.json.write_text(json.dumps(output, indent=2) + "\n", encoding="utf-8")
    names = [title(env, path) for path, env, _ in runs]
    if len(runs) == 1:
        print("| Case | CPU p50 (ms) | CPU p95 (ms) | CPU p99 (ms) | GPU stages p50 (ms) | Redraws |")
        print("| --- | ---: | ---: | ---: | ---: | ---: |")
        for case, values in first_metrics.items():
            cpu = values["total_cpu_wall_ns"]
            gpu = values.get("gpu_stage_sum_ns")
            gpu_text = "%.3f" % (gpu["p50"] / 1e6) if gpu else "-"
            print(f'| {case} | {cpu["p50"]/1e6:.3f} | {cpu["p95"]/1e6:.3f} | {cpu["p99"]/1e6:.3f} | '
                  f'{gpu_text} | {values["redraws"]} |')
        return
    print("CPU wall time inside Screen.render, p50 / p95 in ms; the change is the mean relative to the first report.")
    print("| Case | " + " | ".join(names) + " |")
    print("| --- |" + " ---: |" * len(names))
    for case in first_metrics:
        base = first_metrics[case]["total_cpu_wall_ns"]
        cells = []
        for index, (_, _, metrics) in enumerate(runs):
            cpu = metrics[case]["total_cpu_wall_ns"]
            cell = f'{cpu["p50"]/1e6:.3f} / {cpu["p95"]/1e6:.3f}'
            if index:
                cell += f' ({(cpu["mean"]/base["mean"]-1)*100:+.1f}%)'
            cells.append(cell)
        print(f"| {case} | " + " | ".join(cells) + " |")


if __name__ == "__main__":
    main()
