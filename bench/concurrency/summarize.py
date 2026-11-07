import pandas as pd
import matplotlib.pyplot as plt

df = pd.read_csv("results/raw.csv")
cols = ["strategy", "workers", "throughput_cps", "retries_per_commit", "wasted_work_frac",
        "p95_ms", "queue_delay_p95_ms", "aborts", "invariants_ok"]
with open("results/summary.md", "w") as f:
    for skew, g in df.groupby("skew"):
        f.write(f"## skew = {skew} ({'uniform' if skew == 0 else 'hot-spot Zipf'})\n\n")
        f.write(g.sort_values(["workers", "strategy"])[cols].to_markdown(index=False))
        f.write("\n\n")

metrics = [("throughput_cps", "commits/s"), ("p95_ms", "p95 latency (ms)"),
           ("retries_per_commit", "retries per commit")]
fig, axes = plt.subplots(len(metrics), 2, figsize=(11, 11), sharex=True)
for j, (skew, g) in enumerate(df.groupby("skew")):
    for i, (m, label) in enumerate(metrics):
        ax = axes[i][j]
        for s, gs in g.groupby("strategy"):
            gs = gs.sort_values("workers")
            ax.plot(gs["workers"], gs[m], marker="o", label=s)
        ax.set_xscale("log")
        ax.set_ylabel(label)
        ax.set_title(f"skew={skew}")
        ax.grid(alpha=0.3)
axes[0][0].legend()
for ax in axes[-1]:
    ax.set_xlabel("concurrent writers")
fig.tight_layout()
fig.savefig("results/contention.png", dpi=130)
print(open("results/summary.md").read())
