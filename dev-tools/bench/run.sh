#!/bin/bash
# Head-to-head proxy benchmark: one proxy pinned to CPU 1, wrk + the NGINX backend on CPU 0.
#   ./run.sh setup                       # once: www files + backend on :9200 (needs $BIN/nginx)
#   ./run.sh nginx|haproxy|envoy|caddy|traefik|scaleguard|scaleguard-analytics
# Env: BIN (proxy binaries + wrk, default ./bin), DUR (seconds, 15), WARM (10), C (connections, 64),
#      SG_JAR + SG_DEV (dir with compiled LbHarness) for ScaleGuard, SG_HEAP (default 1g).
HERE=$(cd "$(dirname "$0")" && pwd); cd "$HERE"; mkdir -p logs run
B=${BIN:-$HERE/bin}; name=$1; DUR=${DUR:-15}; C=${C:-64}; SG_HEAP=${SG_HEAP:-1g}
for f in conf/*; do sed "s#BENCH_DIR#$HERE#g" "$f" > "run/$(basename "$f")"; done
if [ "$name" = setup ]; then
  mkdir -p www; printf 'hello world\n' > www/small; head -c 16384 /dev/urandom | base64 -w0 | head -c 16384 > www/16k
  taskset -c 0 $B/nginx -c $HERE/run/backend.conf && echo "backend on :9200"; exit
fi
case $name in
 nginx)   port=8180; cmd="$B/nginx -c $HERE/run/nginx.conf -g 'daemon off;'";;
 haproxy) port=8181; cmd="$B/haproxy -f $HERE/run/haproxy.cfg";;
 envoy)   port=8182; cmd="$B/envoy -c $HERE/run/envoy.yaml --concurrency 1 -l error";;
 caddy)   port=8183; cmd="env GOMAXPROCS=1 $B/caddy run --config $HERE/run/Caddyfile --adapter caddyfile";;
 traefik) port=8184; cmd="env GOMAXPROCS=1 $B/traefik --configFile=$HERE/run/traefik.yml";;
 scaleguard|scaleguard-analytics)
          port=8080; AN="-Dscaleguard.analytics=false"; [ $name = scaleguard-analytics ] && AN="-Dscaleguard.analytics.path=$HERE/run/sghome/an.duckdb"
          rm -rf $HERE/run/sghome; mkdir -p $HERE/run/sghome
          cmd="java -Xms$SG_HEAP -Xmx$SG_HEAP -XX:+UseParallelGC -Duser.home=$HERE/run/sghome -Dport=8085 -Dsport=8445 -Dscaleguard.rateLimit=2000000000 -DadminUser=admin -DadminPassword=TestPass123 -Dhost.health.interval.seconds=3600 $AN -cp $SG_DEV:$SG_JAR LbHarness 127.0.0.1:9200";;
esac
host=127.0.0.1; [[ $name == scaleguard* ]] && host=localhost
(cd $HERE/logs && eval "setsid taskset -c 1 $cmd > $HERE/logs/$name.out 2>&1 &")
for i in $(seq 1 60); do curl -s -o /dev/null -m 1 http://$host:$port/small && break; sleep 0.5; done
pid=$(python3 $HERE/pidport.py $port)
taskset -c 0 $B/wrk -t1 -c$C -d${WARM:-10}s http://$host:$port/small > /dev/null
res="$name"
for path in small 16k; do
  t0=$(python3 $HERE/tree.py $pid ticks); w0=$(date +%s.%N)
  out=$(taskset -c 0 $B/wrk -t1 -c$C -d${DUR}s --latency http://$host:$port/$path)
  t1=$(python3 $HERE/tree.py $pid ticks); w1=$(date +%s.%N)
  rps=$(echo "$out" | awk '/Requests\/sec/{print $2}'); p50=$(echo "$out" | awk '/ 50%/{print $2}'); p99=$(echo "$out" | awk '/ 99%/{print $2}')
  err=$(echo "$out" | grep -E "Non-2xx|Socket errors" | tr -s ' ' | tr '\n' ';')
  cpu=$(python3 -c "print(round(($t1-$t0)/$(getconf CLK_TCK)/($w1-$w0)*100))")
  res="$res | $path rps=$rps p50=$p50 p99=$p99 cpu=${cpu}% $err"
done
rss=$(python3 $HERE/tree.py $pid rss)
echo "$res | rss=${rss}MB"
kill $pid; sleep 1; kill -9 $pid 2>/dev/null; true
