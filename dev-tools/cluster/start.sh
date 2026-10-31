#!/bin/bash
# start.sh <n> <http port> <peer ports, comma-separated>     e.g. ./start.sh 1 7181 7182,7183
# EXTRA="-Dscaleguard.cluster.dr=true" ./start.sh 5 7185 7181,7182,7183,7186   (a DR standby)
# Needs: SG_JAR (the fat jar; default ../../target/scaleguard-1.0-SNAPSHOT.jar). Each node lives in ./home<n>.
HERE=$(cd "$(dirname "$0")" && pwd); cd "$HERE"; n=$1; p=$2; peers=""
SG_JAR=${SG_JAR:-$HERE/../../target/scaleguard-1.0-SNAPSHOT.jar}
[ -f cls/ClusterHarness.class ] || { mkdir -p cls && javac -d cls -cp "$SG_JAR" ClusterHarness.java; }
for q in ${3//,/ }; do peers="$peers,127.0.0.1:$q"; done; peers=${peers#,}
mkdir -p home$n
(setsid nohup java -Xmx384m -Duser.timezone=UTC -Duser.home=$HERE/home$n -Dport=$p -Dsport=$((p+363)) -Dscaleguard.http3=false \
  -Dscaleguard.nodeName=node-$n -Dscaleguard.cluster.advertise=http://127.0.0.1:$p -Dscaleguard.cluster.peers=$peers \
  -Dscaleguard.ledger.testHooks=true -DadminUser=admin -DadminPassword=TestPass123 \
  -Dscaleguard.analytics.path=$HERE/home$n/an.duckdb $EXTRA -cp "cls:$SG_JAR" ClusterHarness > node-$n.log 2>&1 &)
