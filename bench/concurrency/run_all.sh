#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
OUT=results/raw.csv
rm -f "$OUT"
for skew in 0 1.2; do
  for n in 1 10 50 200; do
    for s in optimistic pessimistic queue; do
      python bench.py --strategy "$s" --workers "$n" --skew "$skew" --duration 20 --out "$OUT"
    done
    python bench.py --strategy queue --batch 16 --workers "$n" --skew "$skew" --duration 20 --out "$OUT"
  done
done
python summarize.py
