#!/bin/bash
# Head-to-head proxy benchmark on one machine, with the proxy and the load (wrk + an NGINX backend) on separate CPUs.
#
#   ./run.sh setup                       # once: www files + backend on :9200 (needs $BIN/nginx)
#   ./run.sh nginx|haproxy|envoy|caddy|traefik|scaleguard|scaleguard-analytics
#
# CPU layout (taskset lists):
#   PROXY_CPUS  CPUs for the proxy under test            default 1          e.g. 0-3 on a 4-vCPU proxy
#   LOAD_CPUS   CPUs for wrk and the backend             default 0          e.g. 4-7
# Every proxy gets one worker per proxy CPU (nginx worker_processes, haproxy nbthread, envoy --concurrency,
# GOMAXPROCS for Caddy/Traefik; ScaleGuard sizes its event loops from the CPUs it may run on).
# A 4-vCPU proxy therefore needs an 8-vCPU machine (4 proxy + 4 load), or run wrk from a second machine:
#   LOAD_HOST=10.0.0.5 ./run.sh scaleguard   prints the wrk commands to run on the load machine instead of running them.
#
# Other env: BIN (proxy binaries + wrk, default ./bin), DUR (seconds per measurement, 15), WARM (warm-up seconds,
# 30: the JVM compiles its hot code in the first ~20 s), C (connections, 64 per proxy CPU), SG_JAR + SG_DEV (dir with
# compiled LbHarness) for ScaleGuard, SG_HEAP (default 1g; use 2g on a 4 GB machine).
#
# Output per body size: requests/s, p50/p99 latency, proxy CPU % and proxy CPU µs per request (the most stable
# number on a shared or noisy machine).
HERE=$(cd "$(dirname "$0")" && pwd); cd "$HERE"; mkdir -p logs run
B=${BIN:-$HERE/bin}; name=$1; DUR=${DUR:-15}; SG_HEAP=${SG_HEAP:-1g}
PROXY_CPUS=${PROXY_CPUS:-1}; LOAD_CPUS=${LOAD_CPUS:-0}
ncpus() { python3 -c "
s=0
for part in '$1'.split(','):
    a,_,b=part.partition('-'); s+= (int(b)-int(a)+1) if b else 1
print(s)"; }
W=$(ncpus "$PROXY_CPUS"); LW=$(ncpus "$LOAD_CPUS"); C=${C:-$((64 * W))}
for f in conf/*; do sed "s#BENCH_DIR#$HERE#g; s#BACKEND_WORKERS#$LW#g; s#WORKERS#$W#g" "$f" > "run/$(basename "$f")"; done
if [ "$name" = setup ]; then
  mkdir -p www; printf 'hello world\n' > www/small; head -c 16384 /dev/urandom | base64 -w0 | head -c 16384 > www/16k
  taskset -c $LOAD_CPUS $B/nginx -c $HERE/run/backend.conf && echo "backend on :9200 ($LW workers on CPUs $LOAD_CPUS)"; exit
fi
case $name in
 nginx)   port=8180; cmd="$B/nginx -c $HERE/run/nginx.conf -g 'daemon off;'";;
 haproxy) port=8181; cmd="$B/haproxy -f $HERE/run/haproxy.cfg";;
 envoy)   port=8182; cmd="$B/envoy -c $HERE/run/envoy.yaml --concurrency $W -l error";;
 caddy)   port=8183; cmd="env GOMAXPROCS=$W $B/caddy run --config $HERE/run/Caddyfile --adapter caddyfile";;
 traefik) port=8184; cmd="env GOMAXPROCS=$W $B/traefik --configFile=$HERE/run/traefik.yml";;
 scaleguard|scaleguard-analytics)
          port=8080; AN="-Dscaleguard.analytics=false"; [ $name = scaleguard-analytics ] && AN="-Dscaleguard.analytics.path=$HERE/run/sghome/an.duckdb"
          rm -rf $HERE/run/sghome; mkdir -p $HERE/run/sghome
          cmd="java -Xms$SG_HEAP -Xmx$SG_HEAP -XX:+UseParallelGC -Duser.home=$HERE/run/sghome -Dport=8085 -Dsport=8445 -Dscaleguard.http3=false -Dscaleguard.rateLimit=2000000000 -DadminUser=admin -DadminPassword=TestPass123 -Dhost.health.interval.seconds=3600 $AN -cp $SG_DEV:$SG_JAR LbHarness 127.0.0.1:9200";;
 *) echo "unknown proxy: $name"; exit 1;;
esac
host=127.0.0.1; [[ $name == scaleguard* ]] && host=localhost
(cd $HERE/logs && eval "setsid taskset -c $PROXY_CPUS $cmd > $HERE/logs/$name.out 2>&1 &")
for i in $(seq 60); do curl -s -o /dev/null -m 1 http://$host:$port/small && break; sleep 0.5; done
pid=$(python3 $HERE/pidport.py $port)
if [ -n "$LOAD_HOST" ]; then
  echo "proxy $name running on :$port (pid $pid, CPUs $PROXY_CPUS). On the load machine run:"
  echo "  wrk -t\$(nproc) -c$C -d${WARM:-30}s http://<this-host>:$port/small   # warm-up"
  echo "  wrk -t\$(nproc) -c$C -d${DUR}s --latency http://<this-host>:$port/small"
  echo "  wrk -t\$(nproc) -c$C -d${DUR}s --latency http://<this-host>:$port/16k"
  echo "Stop it with: kill $pid"; exit
fi
taskset -c $LOAD_CPUS $B/wrk -t$LW -c$C -d${WARM:-30}s http://$host:$port/small > /dev/null
res="$name ($W proxy CPU)"
for path in small 16k; do
  t0=$(python3 $HERE/tree.py $pid ticks); w0=$(date +%s.%N)
  out=$(taskset -c $LOAD_CPUS $B/wrk -t$LW -c$C -d${DUR}s --latency http://$host:$port/$path)
  t1=$(python3 $HERE/tree.py $pid ticks); w1=$(date +%s.%N)
  rps=$(echo "$out" | awk '/Requests\/sec/{print $2}'); p50=$(echo "$out" | awk '/ 50%/{print $2}'); p99=$(echo "$out" | awk '/ 99%/{print $2}')
  n=$(echo "$out" | awk '/requests in/{print $1}')
  err=$(echo "$out" | grep -E "Non-2xx|Socket errors" | tr -s ' ' | tr '\n' ';')
  cpu=$(python3 -c "print(round(($t1-$t0)/$(getconf CLK_TCK)/($w1-$w0)*100))")
  us=$(python3 -c "print(round(($t1-$t0)/$(getconf CLK_TCK)*1e6/max(1,$n),2))")
  res="$res | $path rps=$rps p50=$p50 p99=$p99 cpu=${cpu}% cpu_us/req=$us $err"
done
rss=$(python3 $HERE/tree.py $pid rss)
echo "$res | rss=${rss}MB"
kill $pid; sleep 1; kill -9 $pid 2>/dev/null; true
