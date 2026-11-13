#!/usr/bin/env bash
# Read-only recovery verification for a stale Bluetooth DRIVE session.
# Never commands a motor, changes ROS mode, or kills a process.
set -Eeuo pipefail
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROS_SETUP="${ROS_SETUP:-/opt/ros/noetic/setup.bash}"
CHECK="$SCRIPT_DIR/pid_drive_check.py"
fail() { printf '[FAIL] %s\n' "$*" >&2; exit 1; }
[[ -r "$ROS_SETUP" ]] || fail "ROS setup unavailable: $ROS_SETUP"
[[ -f "$CHECK" ]] || fail "Wheel checker unavailable: $CHECK"
set +u
# shellcheck disable=SC1090
source "$ROS_SETUP"
set -u
: "${ROS_MASTER_URI:=http://127.0.0.1:11311}"
export ROS_MASTER_URI

check_no_drive_processes() {
  local nodes
  nodes="$(timeout 6 rosnode list 2>/dev/null)" || fail "ROS node state unavailable (cannot verify no DRIVE nodes)"
  if printf '%s\n' "$nodes" | grep -Eq '^/(ff_pid|pid_safety|pid_astar_planner)$'; then
    fail "DRIVE ROS node still registered; do not clear previous session"
  fi
  # Also detect orphan processes even when they are not registered with ROS.
  local processes
  processes="$(pgrep -af '[f]f_pid_node.py|[p]id_safety.py|[p]id_astar_planner.py|[p]id_drive.sh|[p]id_stop.sh' || true)"
  [[ -z "$processes" ]] || fail "DRIVE/STOP process still active: $processes"
}

timeout 5 rosparam list >/dev/null 2>&1 || fail "ROS Master unavailable: cannot verify physical stop"
check_no_drive_processes
# Require three consecutive fresh wheel-status reports confirming MANUAL
# and both wheel speeds <= the existing conservative stop threshold.
python3 "$CHECK" wheel --mode MANUAL --stopped --timeout 5 || fail "MANUAL + stopped wheel feedback NOT VERIFIED"
check_no_drive_processes
printf '[OK] No DRIVE/STOP processes, both wheels stopped, MANUAL confirmed.\n'
