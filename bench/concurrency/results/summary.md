## skew = 0.0 (uniform)

| strategy    |   workers |   throughput_cps |   retries_per_commit |   wasted_work_frac |   p95_ms |   queue_delay_p95_ms |   aborts | invariants_ok   |
|:------------|----------:|-----------------:|---------------------:|-------------------:|---------:|---------------------:|---------:|:----------------|
| optimistic  |         1 |             32   |                0     |              0     |     76.8 |                nan   |        0 | True            |
| pessimistic |         1 |             32.7 |                0     |              0     |     76.3 |                nan   |        0 | True            |
| queue       |         1 |             26.3 |                0     |              0     |     91.1 |                  0   |        0 | True            |
| queue_b16   |         1 |             29.7 |                0     |              0     |     77.8 |                  0   |        0 | True            |
| optimistic  |        10 |            443.5 |                0.038 |              0.037 |     24.5 |                nan   |        0 | True            |
| pessimistic |        10 |            453.6 |                0     |              0     |     22.6 |                nan   |        0 | True            |
| queue       |        10 |            470.7 |                0     |              0     |     21.5 |                  0.4 |        0 | True            |
| queue_b16   |        10 |            469.7 |                0     |              0     |     21.6 |                  0.5 |        0 | True            |
| optimistic  |        50 |           1376.1 |                0.174 |              0.148 |     65.1 |                nan   |        0 | True            |
| pessimistic |        50 |           1751   |                0     |              0     |     45.5 |                nan   |        0 | True            |
| queue       |        50 |            974.8 |                0     |              0     |     55.9 |                 34.7 |        0 | True            |
| queue_b16   |        50 |           2189.9 |                0     |              0     |     24.1 |                  1.5 |        0 | True            |
| optimistic  |       200 |           1009   |                0.618 |              0.382 |    453.7 |                nan   |        0 | True            |
| pessimistic |       200 |           1602   |                0     |              0     |    236.1 |                nan   |        0 | True            |
| queue       |       200 |            979.8 |                0     |              0     |    214.7 |                193.6 |        0 | True            |
| queue_b16   |       200 |           3408.2 |                0     |              0     |     70.9 |                 44.6 |        0 | True            |

## skew = 1.2 (hot-spot Zipf)

| strategy    |   workers |   throughput_cps |   retries_per_commit |   wasted_work_frac |   p95_ms |   queue_delay_p95_ms |   aborts | invariants_ok   |
|:------------|----------:|-----------------:|---------------------:|-------------------:|---------:|---------------------:|---------:|:----------------|
| optimistic  |         1 |             39.4 |                0     |              0     |     26   |                nan   |        0 | True            |
| pessimistic |         1 |             39.6 |                0     |              0     |     26.6 |                nan   |        0 | True            |
| queue       |         1 |             39.2 |                0     |              0     |     27.4 |                  0   |        0 | True            |
| queue_b16   |         1 |             37.9 |                0     |              0     |     37   |                  0   |        0 | True            |
| optimistic  |        10 |             89.9 |                2.218 |              0.689 |    612.2 |                nan   |        1 | True            |
| pessimistic |        10 |             97   |                0     |              0     |    262.2 |                nan   |        0 | True            |
| queue       |        10 |            469.8 |                0     |              0     |     21.5 |                  0.4 |        0 | True            |
| queue_b16   |        10 |            467.8 |                0     |              0     |     21.5 |                  0.4 |        0 | True            |
| optimistic  |        50 |            106.5 |                9.039 |              0.901 |   1548.3 |                nan   |      201 | True            |
| pessimistic |        50 |             84.4 |                0     |              0     |   2451   |                nan   |        0 | True            |
| queue       |        50 |           1000.3 |                0     |              0     |     51.6 |                 30.5 |        0 | True            |
| queue_b16   |        50 |           2151.5 |                0     |              0     |     24.4 |                  1.7 |        0 | True            |
| optimistic  |       200 |             57.2 |               23.801 |              0.96  |   4015.8 |                nan   |      513 | True            |
| pessimistic |       200 |             48   |                0     |              0     |  20692.7 |                nan   |        0 | True            |
| queue       |       200 |            989.2 |                0     |              0     |    210.6 |                189.5 |        0 | True            |
| queue_b16   |       200 |           3124.5 |                0     |              0     |     68.9 |                 43   |        0 | True            |

