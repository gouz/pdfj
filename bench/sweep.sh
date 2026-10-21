#!/bin/sh
# Capacity sweep, pdfj vs pdfrs, everything in Docker on the same machine (k6 included):
# for each config, hold VUS concurrent users for DURATION, print req/s, p95 and peak memory of the server container.
# Usage: PDFRS_DIR=../pdfrs sh bench/sweep.sh [config...]   (or PDFRS_IMAGE=ghcr.io/gouz/pdfrs:0.5.1: no pdfrs build)
#   configs: j (pdfj, all cores, heap not bounded), jx (pdfj, all cores, 256 MB heap: the image's default), j1 (pdfj, 1 core), rs (pdfrs, all cores), rs1 (pdfrs, 1 core)
cd "$(dirname "$0")/.." || exit 1
PDFRS_DIR=$(cd "${PDFRS_DIR:-../pdfrs}" 2>/dev/null && pwd)
NET=pdfbench
DURATION=${DURATION:-15s}
VUS=${VUS:-1 5 10 25 50 100 200}
docker network create $NET >/dev/null 2>&1
docker build -q -t pdfj . >/dev/null || exit 1
RS=${PDFRS_IMAGE:-pdfrs}
case " ${*:-j j1 rs rs1} " in *" rs"*) [ -n "$PDFRS_IMAGE" ] || docker build -q -t pdfrs "$PDFRS_DIR" >/dev/null || exit 1 ;; esac

start() { # name
  docker rm -f srv >/dev/null 2>&1
  case $1 in
    j)   docker run -d --name srv --network $NET -v "$PWD/demo":/images:ro -e IMAGES_DIR=/images -e JAVA_TOOL_OPTIONS= pdfj ;;
    jx)  docker run -d --name srv --network $NET -v "$PWD/demo":/images:ro -e IMAGES_DIR=/images -e JAVA_TOOL_OPTIONS=-Xmx256m pdfj ;;
    j1)  docker run -d --name srv --network $NET -v "$PWD/demo":/images:ro -e IMAGES_DIR=/images -e WORKERS=1 --cpus 1 pdfj ;;
    rs)  docker run -d --name srv --network $NET -v "$PWD/demo":/images:ro -e IMAGES_DIR=/images "$RS" ;;
    rs1) docker run -d --name srv --network $NET -v "$PWD/demo":/images:ro -e IMAGES_DIR=/images -e WORKERS=1 --cpus 1 "$RS" ;;
  esac >/dev/null
  sleep 3
  # the JVM compiles its hot paths in the first seconds: warm it up before measuring
  docker run --rm --network $NET -v "$PWD":/w -w /w/bench grafana/k6:latest run -q --vus 10 --duration 10s -e URL=http://srv:3000 k6.js >/dev/null 2>&1
}

echo "server,vus,req_s,p95_ms,fail_rate,peak_mem_mb"
for cfg in ${@:-j j1 rs rs1}; do
  start "$cfg"
  for v in $VUS; do
    docker stats --format '{{.MemUsage}}' srv > /tmp/pdfbench-mem.txt 2>/dev/null & statpid=$!
    docker run --rm --network $NET -v "$PWD":/w -w /w/bench grafana/k6:latest run -q --vus "$v" --duration "$DURATION" \
      -e URL=http://srv:3000 --summary-export /w/target-bench.json k6.js >/dev/null 2>&1
    kill $statpid 2>/dev/null; wait $statpid 2>/dev/null
    peak=$(sed 's/\x1b\[[0-9;]*[A-Za-z]//g' /tmp/pdfbench-mem.txt | awk '{ v = $1; u = v; sub(/[0-9.]+/, "", u); sub(/[A-Za-z]+/, "", v);
      m = (u == "GiB") ? v * 1024 : (u == "KiB") ? v / 1024 : v; if (m > p) p = m } END { printf "%d", p }')
    docker run --rm -v "$PWD":/w -w /w node:22-alpine node -e '
      const m = require("/w/target-bench.json").metrics;
      console.log([process.argv[1], process.argv[2], m.http_reqs.rate.toFixed(0), m.http_req_duration["p(95)"].toFixed(0),
        m.http_req_failed.value, process.argv[3]].join(","))' "$cfg" "$v" "$peak"
  done
done
docker rm -f srv >/dev/null 2>&1
rm -f target-bench.json
