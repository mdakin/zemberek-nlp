#!/usr/bin/env python3
"""
bench_java21_vs_java25.py

Runs comprehensive benchmarks comparing Temurin OpenJDK 21 LTS vs Temurin OpenJDK 25 LTS on Zemberek:
1. Decoding Modes Benchmark (Exact Viterbi, Beam-8, Greedy)
2. Multi-threaded Core Scaling (1, 4, 8, 16 threads)
3. Startup and Dictionary Load Time
4. Raw Morphological Analysis Throughput
"""

import os
import sys
import json
import time
import subprocess
from pathlib import Path

JAVA21 = "/home/dndara/sdk/temurin-21/bin/java"
JAVA25 = "/home/dndara/sdk/temurin-25/bin/java"
CP = "experiment/target/classes:morphology/target/classes:core/target/classes:dependencies/*"
CORPUS = "data/ambiguity/sources/UD_Turkish-BOUN/boun_test_canonical.jsonl"
MODEL = "data/ambiguity/model-distilled-v3.bin"


def get_java_version(java_bin: str) -> str:
    res = subprocess.run([java_bin, "-version"], capture_output=True, text=True)
    first_line = (res.stderr or res.stdout).splitlines()[0]
    return first_line.strip()


def run_benchmark_modes(java_bin: str, out_json: str, threads: int = 1, warmup: int = 4, iters: int = 8):
    cmd = [
        java_bin,
        "-Xms2g", "-Xmx4g",
        "-cp", CP,
        "zemberek.morphology.ambiguity.dataset.BenchmarkAmbiguityResolver",
        "-c", CORPUS,
        "-m", MODEL,
        "-w", str(warmup),
        "-it", str(iters),
        "-t", str(threads),
        "-benchmarkAllModes",
        "-o", out_json
    ]
    print(f"Running modes benchmark with {java_bin} (threads={threads})...")
    start = time.time()
    res = subprocess.run(cmd, capture_output=True, text=True)
    duration = time.time() - start
    if res.returncode != 0:
        print(f"[ERROR] Command failed with code {res.returncode}:\n{res.stderr}", file=sys.stderr)
    return duration, res.stdout


def run_multithread_bench(java_bin: str, out_json: str, threads: int):
    cmd = [
        java_bin,
        "-Xms2g", "-Xmx4g",
        "-cp", CP,
        "zemberek.morphology.ambiguity.dataset.BenchmarkAmbiguityResolver",
        "-c", CORPUS,
        "-m", MODEL,
        "-w", "3",
        "-it", "6",
        "-t", str(threads),
        "-b", "8",
        "-o", out_json
    ]
    print(f"Running MT benchmark with {java_bin} (threads={threads})...")
    start = time.time()
    res = subprocess.run(cmd, capture_output=True, text=True)
    duration = time.time() - start
    return duration, res.stdout


def run_startup_bench(java_bin: str, runs: int = 5) -> float:
    # Measures time to cold-initialize TurkishMorphology.createWithDefaults()
    code = """
import zemberek.morphology.TurkishMorphology;
public class StartupTest {
    public static void main(String[] args) {
        long t0 = System.nanoTime();
        TurkishMorphology m = TurkishMorphology.createWithDefaults();
        long t1 = System.nanoTime();
        System.out.println("STARTUP_MS=" + ((t1 - t0) / 1_000_000.0));
    }
}
"""
    # Write temporary file
    src_path = "/tmp/StartupTest.java"
    with open(src_path, "w") as f:
        f.write(code)

    javac = str(Path(java_bin).parent / "javac")
    subprocess.run([javac, "-cp", CP, "-d", "/tmp", src_path], check=True)

    times = []
    for _ in range(runs):
        res = subprocess.run([java_bin, "-Xms1g", "-Xmx2g", "-cp", f"/tmp:{CP}", "StartupTest"],
                             capture_output=True, text=True)
        for line in res.stdout.splitlines():
            if line.startswith("STARTUP_MS="):
                times.append(float(line.split("=")[1]))
    return sum(times) / len(times) if times else 0.0


def main():
    print("=" * 80)
    print("      ZEMBEREK JVM PERFORMANCE BENCHMARK: JAVA 21 LTS vs JAVA 25 LTS      ")
    print("=" * 80)
    v21 = get_java_version(JAVA21)
    v25 = get_java_version(JAVA25)
    print(f"JVM 21: {v21}")
    print(f"JVM 25: {v25}")
    print(f"Corpus: {CORPUS}")
    print(f"Model:  {MODEL}")
    print("=" * 80)

    # 1. Startup Benchmark
    print("\n--- [1/3] Benchmarking Cold Startup & Lexicon Deserialization ---")
    startup_21 = run_startup_bench(JAVA21, runs=5)
    print(f"Java 21 Startup Time: {startup_21:.2f} ms")
    startup_25 = run_startup_bench(JAVA25, runs=5)
    print(f"Java 25 Startup Time: {startup_25:.2f} ms")
    startup_delta = ((startup_25 - startup_21) / startup_21) * 100

    # 2. Decoding Modes Benchmark (Single Threaded)
    print("\n--- [2/3] Benchmarking Decoding Modes (Exact Viterbi, Beam-8, Greedy) ---")
    out21_modes = "/tmp/bench_modes_java21.json"
    out25_modes = "/tmp/bench_modes_java25.json"

    run_benchmark_modes(JAVA21, out21_modes, threads=1, warmup=4, iters=8)
    run_benchmark_modes(JAVA25, out25_modes, threads=1, warmup=4, iters=8)

    with open(out21_modes) as f:
        data21_modes = json.load(f)
    with open(out25_modes) as f:
        data25_modes = json.load(f)

    # 3. Multi-Threaded Throughput Benchmark
    print("\n--- [3/3] Benchmarking Multi-Threaded Core Scaling (1, 4, 8, 16 threads) ---")
    mt_results_21 = {}
    mt_results_25 = {}

    for t in [1, 4, 8, 16]:
        out21_t = f"/tmp/bench_mt_{t}_java21.json"
        out25_t = f"/tmp/bench_mt_{t}_java25.json"
        run_multithread_bench(JAVA21, out21_t, threads=t)
        run_multithread_bench(JAVA25, out25_t, threads=t)

        with open(out21_t) as f:
            d21 = json.load(f)
            mt_results_21[t] = d21["results"][0]
        with open(out25_t) as f:
            d25 = json.load(f)
            mt_results_25[t] = d25["results"][0]

    # Print Summary Report
    print("\n" + "=" * 90)
    print("                     FINAL JVM COMPARISON REPORT                     ")
    print("=" * 90)

    print("\n### 1. Cold Startup & Lexicon Loading Time (Mean of 5 cold runs)")
    print(f"  Java 21: {startup_21:6.1f} ms")
    print(f"  Java 25: {startup_25:6.1f} ms ({startup_delta:+5.1f}%)")

    print("\n### 2. Single-Thread Throughput by Decoding Mode (Words / Sec)")
    print(f"{'Mode':<20} {'Java 21 (w/s)':<18} {'Java 25 (w/s)':<18} {'Speedup':<12}")
    print("-" * 68)

    for r21, r25 in zip(data21_modes["results"], data25_modes["results"]):
        mode_name = r21["modelName"].split("[")[-1].replace("]", "")
        w21 = r21["e2eWordsPerSec"]
        w25 = r25["e2eWordsPerSec"]
        speedup = ((w25 - w21) / w21) * 100.0
        print(f"{mode_name:<20} {w21:12,.0f}       {w25:12,.0f}       {speedup:+6.2f}%")

    print("\n### 3. Disambiguation Stage Throughput (Decoder Alone, Words / Sec)")
    print(f"{'Mode':<20} {'Java 21 (w/s)':<18} {'Java 25 (w/s)':<18} {'Speedup':<12}")
    print("-" * 68)

    for r21, r25 in zip(data21_modes["results"], data25_modes["results"]):
        mode_name = r21["modelName"].split("[")[-1].replace("]", "")
        dw21 = r21["disambigWordsPerSec"]
        dw25 = r25["disambigWordsPerSec"]
        speedup = ((dw25 - dw21) / dw21) * 100.0
        print(f"{mode_name:<20} {dw21:12,.0f}       {dw25:12,.0f}       {speedup:+6.2f}%")

    print("\n### 4. Mean & Tail Latency (Exact Viterbi Mode)")
    ev21 = data21_modes["results"][0]
    ev25 = data25_modes["results"][0]
    print(f"{'Latency Metric':<20} {'Java 21 (ms)':<18} {'Java 25 (ms)':<18} {'Delta':<12}")
    print("-" * 68)
    for metric, key in [("Mean Latency", "latencyMeanMs"), ("p50 (Median)", "latencyP50Ms"),
                        ("p95 Latency", "latencyP95Ms"), ("p99 Latency", "latencyP99Ms"),
                        ("Max Latency", "latencyMaxMs")]:
        v_21 = ev21[key]
        v_25 = ev25[key]
        diff = ((v_25 - v_21) / v_21) * 100.0
        print(f"{metric:<20} {v_21:10.3f} ms     {v_25:10.3f} ms     {diff:+6.2f}%")

    print("\n### 5. Multi-Threaded Core Scaling (Beam-8 Mode)")
    print(f"{'Threads':<12} {'Java 21 (w/s)':<18} {'Java 25 (w/s)':<18} {'Speedup':<12}")
    print("-" * 60)
    for t in [1, 4, 8, 16]:
        r21 = mt_results_21[t]
        r25 = mt_results_25[t]
        tw21 = r21["multiThreadWordsPerSec"] if r21.get("multiThreadWordsPerSec") else r21["e2eWordsPerSec"]
        tw25 = r25["multiThreadWordsPerSec"] if r25.get("multiThreadWordsPerSec") else r25["e2eWordsPerSec"]
        delta = ((tw25 - tw21) / tw21) * 100.0
        print(f"{t:<12} {tw21:12,.0f}       {tw25:12,.0f}       {delta:+6.2f}%")

    print("=" * 90)


if __name__ == "__main__":
    main()

