#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p SortingDemo/runtime/classes
pidfile=SortingDemo/runtime/mqtt-vehicles.pid
alive() {
  [[ -f "$pidfile" ]] || return 1
  local fleet_pid
  fleet_pid=$(cat "$pidfile")
  [[ -r "/proc/$fleet_pid/cmdline" ]] && tr '\0' ' ' < "/proc/$fleet_pid/cmdline" | grep -q 'SortingMqttFleet'
}
compile() {
  javac -cp 'build/install/opentcs/opentcs-kernel/lib/*:SortingDemo/runtime/classes' -d SortingDemo/runtime/classes \
    SortingDemo/SortingTraffic.java SortingDemo/SortingMetrics.java SortingDemo/SortingPickup.java \
    SortingDemo/SortingTrafficTest.java SortingDemo/SortingSchedulerTest.java SortingDemo/SortingPickupTest.java \
    SortingDemo/SortingSimulation.java SortingDemo/SortingMqttVehicle.java SortingDemo/SortingMqttFleet.java SortingDemo/SortingMqttTest.java SortingDemo/SortingMqttIntegrationTest.java
}
case "${1:-status}" in
  start)
    if alive; then echo 'MQTT vehicle fleet already running.'; exit 0; fi
    compile
    python3 - <<'PY'
from pathlib import Path
import subprocess, time
with open('SortingDemo/runtime/mqtt-vehicles.log', 'a') as log:
    p = subprocess.Popen(['bash', 'SortingDemo/mqtt-vehicles.sh', 'run'], stdin=subprocess.DEVNULL,
                         stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
Path('SortingDemo/runtime/mqtt-vehicles.pid').write_text(str(p.pid)+'\n')
time.sleep(3)
if p.poll() is not None:
    raise SystemExit('Fleet failed; see SortingDemo/runtime/mqtt-vehicles.log')
PY
    echo 'MQTT fleet started; check one ONLINE entry per configured vehicle in SortingDemo/runtime/mqtt-vehicles.log.'
    ;;
  run)
    exec 9>SortingDemo/runtime/mqtt-vehicles.lock
    flock -n 9 || { echo 'Another MQTT fleet is running.' >&2; exit 1; }
    exec java -Xmx512m -cp 'SortingDemo/runtime/classes:build/install/opentcs/opentcs-kernel/lib/*' SortingMqttFleet
    ;;
  stop)
    if alive; then
      kill "$(cat "$pidfile")"
      for ((i=0;i<20;i++)); do alive || break; sleep 1; done
      if alive; then echo 'Fleet is still stopping; inspect its log.' >&2; exit 1; fi
    fi
    rm -f "$pidfile"
    echo 'MQTT fleet stopped; vehicle checkpoints preserved.'
    ;;
  status)
    if alive; then echo "MQTT fleet running (PID $(cat "$pidfile"))"; else echo 'MQTT fleet stopped'; fi
    tail -n 10 SortingDemo/runtime/mqtt-vehicles.log 2>/dev/null || true
    ;;
  verify)
    compile
    exec java -ea -cp 'SortingDemo/runtime/classes:build/install/opentcs/opentcs-kernel/lib/*' SortingMqttIntegrationTest
    ;;
  check)
    compile
    exec java -ea -cp 'SortingDemo/runtime/classes:build/install/opentcs/opentcs-kernel/lib/*' SortingMqttTest
    ;;
  *) echo 'Usage: SortingDemo/mqtt-vehicles.sh {start|stop|status|check|verify}' >&2; exit 2;;
esac
