"""Compare matching benchmark runs using their raw, frame-correlated CSV samples."""
import argparse
import csv
import json
from pathlib import Path
from statistics import mean


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
    groups = {}
    for case in report["results"]:
        csv_path = path.parent / f'{case["repeat"]}-{case["name"]}.csv'
        with csv_path.open(encoding="utf-8", newline="") as stream:
            rows = list(csv.DictReader(stream))
        if len(rows) != report["environment"]["measuredFrames"]:
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
    names = set(groups)
    if len(report["results"]) != len(names) * report["environment"]["repeats"]:
        raise ValueError(f"Incomplete repetitions: {path}")
    metrics = {}
    for name, rows in groups.items():
        columns = [key for key in rows[0] if key.endswith("_ns") and key != "start_relative_ns"]
        columns += ["render_bytes", "compose_bytes", "compose_calls"]
        values = {key: distribution([int(row[key]) for row in rows if row[key] != ""]) for key in columns}
        if report["environment"]["backend"] == "OPENGL":
            gpu_keys = [key for key in rows[0] if key.startswith("gpu_") and key.endswith("_ns")]
            values["gpu_stage_sum_ns"] = distribution([sum(int(row[key]) for key in gpu_keys) for row in rows])
        values["redraws"] = sum(row["rendered"] == "true" for row in rows)
        values["max_cached_items"] = max(int(row["cached_items"]) for row in rows)
        values["pending_item_frames"] = sum(int(row["pending_items"]) > 0 for row in rows)
        metrics[name] = values
    return report["environment"], metrics


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path, help="Path to the baseline report.json")
    parser.add_argument("candidate", type=Path, help="Path to the candidate report.json")
    parser.add_argument("--json", type=Path, help="Optional output with pooled raw-frame distributions")
    args = parser.parse_args()
    old_env, old = load_run(args.baseline)
    new_env, new = load_run(args.candidate)
    comparable = ["java", "cpu", "gpu", "glVersion", "backend", "width", "height", "guiScale", "vsync",
                  "frameLimit", "warmupFrames", "pipelineWarmupFrames", "measuredFrames", "repeats", "allocations", "resourcePacks", "windowMode", "focusSource", "windowIsolation"]
    changed = [key for key in comparable if old_env.get(key) != new_env.get(key)]
    if changed or old.keys() != new.keys():
        raise ValueError(f"Runs do not have matching conditions: {changed}")
    output = {"environment": new_env, "baseline": old, "candidate": new}
    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps(output, indent=2) + "\n", encoding="utf-8")
    print("| Scenario | CPU p50 before/after (ms) | CPU p95 before/after (ms) | Mean CPU change |")
    print("| --- | ---: | ---: | ---: |")
    for name in old:
        a, b = old[name]["total_cpu_wall_ns"], new[name]["total_cpu_wall_ns"]
        print(f'| {name} | {a["p50"]/1e6:.3f} / {b["p50"]/1e6:.3f} | '
              f'{a["p95"]/1e6:.3f} / {b["p95"]/1e6:.3f} | {(b["mean"]/a["mean"]-1)*100:+.1f}% |')


if __name__ == "__main__":
    main()
