# Cluster test harness (ledger mode)

Local multi-node ScaleGuard clusters on one machine, with no database. Needs a backend answering `GET /small` on
127.0.0.1:9200 (`../bench/run.sh setup`, or `python3 -m http.server 9200` in a folder with a `small` file).

```bash
mvn -q -DskipTests package          # builds target/scaleguard-1.0-SNAPSHOT.jar
cd dev-tools/cluster
./fresh.sh && sleep 12              # 3 nodes: admin ports 7181-7183
python3 ledger_test.py              # election, forwarding to master, convergence, failover, quorum loss, tamper, joiner (27 checks)
./fresh.sh && sleep 12 && python3 setup.py
python3 oauth_cluster.py            # refresh tokens across nodes
PM=6000 python3 rl_test.py shared,strict   # one client through two nodes: admitted vs the ideal
python3 an_test.py                  # needs node 4: ./start.sh 4 7184 7181 — analytics through the master
python3 dr_test.py                  # primary site 1-3 + DR site 5-6, primary loss, promotion (starts its own nodes)
./stop.sh
```
`./kill9.sh <n>` kills one node; `EXTRA=… ./start.sh` passes extra -D flags (e.g. `-Dscaleguard.cluster.dr=true`).
