#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p SortingDemo/runtime/classes
if [[ SortingDemo/SortingPickup.java -nt SortingDemo/runtime/classes/SortingSimulation.class ]] || [[ SortingDemo/SortingPickupTest.java -nt SortingDemo/runtime/classes/SortingSimulation.class ]] || [[ SortingDemo/SortingSchedulerTest.java -nt SortingDemo/runtime/classes/SortingSimulation.class ]] || [[ ! -f SortingDemo/runtime/classes/SortingSimulation.class ]] || [[ SortingDemo/SortingSimulation.java -nt SortingDemo/runtime/classes/SortingSimulation.class ]] || [[ SortingDemo/SortingTraffic.java -nt SortingDemo/runtime/classes/SortingSimulation.class ]] || [[ SortingDemo/SortingTrafficTest.java -nt SortingDemo/runtime/classes/SortingSimulation.class ]] || [[ SortingDemo/SortingMetrics.java -nt SortingDemo/runtime/classes/SortingSimulation.class ]]; then
  javac -cp 'build/install/opentcs/opentcs-kernel/lib/*' -d SortingDemo/runtime/classes SortingDemo/SortingTraffic.java SortingDemo/SortingMetrics.java SortingDemo/SortingTrafficTest.java SortingDemo/SortingSimulation.java SortingDemo/SortingSchedulerTest.java SortingDemo/SortingPickup.java SortingDemo/SortingPickupTest.java
fi
case "${1:-status}" in
  start)
    if [[ -f SortingDemo/runtime/simulation.pid ]] && kill -0 "$(cat SortingDemo/runtime/simulation.pid)" 2>/dev/null; then
      echo 'Simulation generator is already running.'
      exit 0
    fi
    rm -f SortingDemo/runtime/drain.request
    python3 - <<'START'
from pathlib import Path
import subprocess
import time
with open('SortingDemo/runtime/simulation.log', 'a') as log:
    process = subprocess.Popen(['bash', 'SortingDemo/simulation.sh', 'run'],
                               stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                               start_new_session=True)
Path('SortingDemo/runtime/simulation.pid').write_text(str(process.pid) + '\n')
time.sleep(2)
if process.poll() is not None:
    raise SystemExit('Generator failed to start; see SortingDemo/runtime/simulation.log')
START
    echo "Simulation started; log: SortingDemo/runtime/simulation.log"
    ;;
  stop)
    if [[ -f SortingDemo/runtime/simulation.pid ]]; then
      simulation_pid=$(cat SortingDemo/runtime/simulation.pid)
      if [[ -r "/proc/$simulation_pid/cmdline" ]] && tr '\0' ' ' < "/proc/$simulation_pid/cmdline" | rg -q 'SortingSimulation(\.java)? run'; then
        kill "$simulation_pid"
      fi
      rm -f SortingDemo/runtime/simulation.pid
    fi
    echo 'Generator stopped. Submitted transport orders may finish; start resumes their checkpoint without resetting vehicles.'
    ;;
  run)
    exec 9>SortingDemo/runtime/simulation.lock
    flock -n 9 || { echo 'Another generator is already running.'; exit 1; }
    exec java -Xmx768m --class-path 'SortingDemo/runtime/classes:build/install/opentcs/opentcs-kernel/lib/*' SortingSimulation run
    ;;
  setup|status|check|stats|measure|drain)
    exec java -Xmx768m --class-path 'SortingDemo/runtime/classes:build/install/opentcs/opentcs-kernel/lib/*' SortingSimulation "${1:-status}"
    ;;
  *) echo 'Usage: SortingDemo/simulation.sh {setup|start|stop|status|run|check|stats|measure|drain}' >&2; exit 2 ;;
esac
