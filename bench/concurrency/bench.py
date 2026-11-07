#!/usr/bin/env python3
"""Write-contention benchmark on a ledger schema.

Strategies: optimistic (version check + retry), pessimistic (row locks),
queue (single serial writer, optional batching).
"""
import argparse
import csv
import math
import os
import queue
import random
import threading
import time

import numpy as np
import psycopg

INITIAL = 1_000_000_000


def setup(dsn, n_accounts):
    with psycopg.connect(dsn, autocommit=True) as c:
        c.execute("DROP TABLE IF EXISTS entries")
        c.execute("DROP TABLE IF EXISTS accounts")
        c.execute("""CREATE TABLE accounts(
                       id int PRIMARY KEY,
                       balance bigint NOT NULL,
                       version int NOT NULL DEFAULT 0)""")
        # No FK on purpose: FK checks take KEY SHARE locks and add a second contention source.
        c.execute("""CREATE TABLE entries(
                       id bigserial PRIMARY KEY,
                       txn_id bigint NOT NULL,
                       account_id int NOT NULL,
                       amount bigint NOT NULL,
                       created_at timestamptz NOT NULL DEFAULT now())""")
        c.execute("INSERT INTO accounts(id, balance) SELECT g, %s FROM generate_series(0, %s) g",
                  (INITIAL, n_accounts - 1))


def make_sampler(n, skew, seed):
    rng = np.random.default_rng(seed)
    p = None
    if skew > 0:
        w = 1.0 / np.arange(1, n + 1) ** skew
        p = w / w.sum()

    def pick():
        while True:
            a, b = rng.choice(n, size=2, p=p)
            if a != b:
                return int(a), int(b), int(rng.integers(1, 1000))
    return pick


def apply_transfer(cur, a, b, amt, versions=None):
    """Update both accounts in id order (prevents deadlocks). False if a version check fails."""
    deltas = {a: -amt, b: amt}
    for acc in sorted(deltas):
        if versions is None:
            cur.execute("UPDATE accounts SET balance = balance + %s, version = version + 1 "
                        "WHERE id = %s", (deltas[acc], acc))
        else:
            cur.execute("UPDATE accounts SET balance = balance + %s, version = version + 1 "
                        "WHERE id = %s AND version = %s", (deltas[acc], acc, versions[acc]))
        if cur.rowcount != 1:
            return False
    txn = random.getrandbits(62)
    cur.executemany("INSERT INTO entries(txn_id, account_id, amount) VALUES (%s, %s, %s)",
                    [(txn, a, -amt), (txn, b, amt)])
    return True


RETRYABLE = (psycopg.errors.DeadlockDetected, psycopg.errors.SerializationFailure)


def run_optimistic(conn, a, b, amt, args):
    think = args.think_ms / 1000
    attempts, wasted = 0, 0.0
    while attempts < args.max_retries:
        attempts += 1
        try:
            with conn.cursor() as cur:
                cur.execute("SELECT id, version FROM accounts WHERE id = ANY(%s)", ([a, b],))
                versions = dict(cur.fetchall())
            conn.commit()                      # snapshot taken, no locks held while "working"
            time.sleep(think)
            with conn.cursor() as cur:
                if apply_transfer(cur, a, b, amt, versions):
                    conn.commit()
                    return True, attempts, wasted
            conn.rollback()
        except RETRYABLE:
            conn.rollback()
        wasted += think
        time.sleep(random.uniform(0, min(0.05, 0.001 * 2 ** attempts)))  # jittered backoff
    return False, attempts, wasted


def run_pessimistic(conn, a, b, amt, args):
    think = args.think_ms / 1000
    attempts = 0
    while True:
        attempts += 1
        try:
            with conn.cursor() as cur:
                cur.execute("SELECT id FROM accounts WHERE id = ANY(%s) ORDER BY id FOR UPDATE",
                            ([a, b],))
                cur.fetchall()
                time.sleep(think)              # "work" while holding the locks
                apply_transfer(cur, a, b, amt)
            conn.commit()
            return True, attempts, 0.0
        except RETRYABLE:
            conn.rollback()
            if attempts >= args.max_retries:
                return False, attempts, 0.0


class SerialQueue:
    def __init__(self, dsn, batch):
        self.q = queue.Queue()
        self.batch = batch
        self.stop = threading.Event()
        self.conn = psycopg.connect(dsn)
        self.thread = threading.Thread(target=self._loop, daemon=True)
        self.thread.start()

    def submit(self, a, b, amt):
        item = {"a": a, "b": b, "amt": amt, "t_sub": time.perf_counter(),
                "done": threading.Event()}
        self.q.put(item)
        item["done"].wait()
        return item

    def _loop(self):
        while not (self.stop.is_set() and self.q.empty()):
            try:
                items = [self.q.get(timeout=0.1)]
            except queue.Empty:
                continue
            while len(items) < self.batch:
                try:
                    items.append(self.q.get_nowait())
                except queue.Empty:
                    break
            t_start = time.perf_counter()
            with self.conn.cursor() as cur:
                for it in items:
                    apply_transfer(cur, it["a"], it["b"], it["amt"])
            self.conn.commit()
            for it in items:
                it["t_start"] = t_start
                it["done"].set()

    def close(self):
        self.stop.set()
        self.thread.join()
        self.conn.close()


def worker(i, args, deadline, results, sq):
    pick = make_sampler(args.accounts, args.skew, args.seed + i)
    fn = {"optimistic": run_optimistic, "pessimistic": run_pessimistic}.get(args.strategy)
    conn = None if sq else psycopg.connect(args.dsn)
    local = []
    while time.perf_counter() < deadline:
        a, b, amt = pick()
        t0 = time.perf_counter()
        if sq:
            time.sleep(args.think_ms / 1000)   # prepare change on own snapshot
            it = sq.submit(a, b, amt)
            local.append((time.perf_counter() - t0, 1, 0.0, it["t_start"] - it["t_sub"], True))
        else:
            ok, attempts, wasted = fn(conn, a, b, amt, args)
            local.append((time.perf_counter() - t0, attempts, wasted, math.nan, ok))
    if conn:
        conn.close()
    results.extend(local)


def check_invariants(dsn, n_accounts, commits):
    with psycopg.connect(dsn) as c:
        total = c.execute("SELECT sum(balance) FROM accounts").fetchone()[0]
        esum, ecount = c.execute(
            "SELECT coalesce(sum(amount), 0), count(*) FROM entries").fetchone()
    return int(total) == INITIAL * n_accounts and int(esum) == 0 and ecount == 2 * commits


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--dsn", default="postgresql://bench:bench@localhost:5433/ledger")
    p.add_argument("--strategy", choices=["optimistic", "pessimistic", "queue"], required=True)
    p.add_argument("--workers", type=int, default=10)
    p.add_argument("--accounts", type=int, default=1000)
    p.add_argument("--skew", type=float, default=0.0, help="Zipf exponent; 0 = uniform")
    p.add_argument("--think-ms", type=float, default=20.0)
    p.add_argument("--duration", type=float, default=20.0)
    p.add_argument("--max-retries", type=int, default=50)
    p.add_argument("--batch", type=int, default=1, help="queue only")
    p.add_argument("--seed", type=int, default=42)
    p.add_argument("--out", default="results/raw.csv")
    args = p.parse_args()

    setup(args.dsn, args.accounts)
    sq = SerialQueue(args.dsn, args.batch) if args.strategy == "queue" else None
    results = []
    start = time.perf_counter()
    deadline = start + args.duration
    threads = [threading.Thread(target=worker, args=(i, args, deadline, results, sq))
               for i in range(args.workers)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    elapsed = time.perf_counter() - start
    if sq:
        sq.close()

    ok = [r for r in results if r[4]]
    commits = len(ok)
    attempts = sum(r[1] for r in results)
    wasted = sum(r[2] for r in results)
    lat = np.array([r[0] for r in ok]) * 1000
    qd = np.array([r[3] for r in ok if not math.isnan(r[3])]) * 1000
    useful = commits * args.think_ms / 1000

    label = args.strategy if args.strategy != "queue" or args.batch == 1 else f"queue_b{args.batch}"
    row = {
        "strategy": label, "workers": args.workers, "skew": args.skew,
        "accounts": args.accounts, "think_ms": args.think_ms, "elapsed_s": round(elapsed, 2),
        "commits": commits, "aborts": len(results) - commits,
        "throughput_cps": round(commits / elapsed, 1),
        "retries_per_commit": round((attempts - len(results)) / max(commits, 1), 3),
        "wasted_work_frac": round(wasted / max(wasted + useful, 1e-9), 3),
        "p50_ms": round(float(np.percentile(lat, 50)), 1) if commits else math.nan,
        "p95_ms": round(float(np.percentile(lat, 95)), 1) if commits else math.nan,
        "p99_ms": round(float(np.percentile(lat, 99)), 1) if commits else math.nan,
        "queue_delay_p95_ms": round(float(np.percentile(qd, 95)), 1) if len(qd) else math.nan,
        "invariants_ok": check_invariants(args.dsn, args.accounts, commits),
    }
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    new = not os.path.exists(args.out)
    with open(args.out, "a", newline="") as f:
        w = csv.DictWriter(f, fieldnames=row.keys())
        if new:
            w.writeheader()
        w.writerow(row)
    print(row)


if __name__ == "__main__":
    main()
