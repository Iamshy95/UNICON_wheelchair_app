#!/usr/bin/env python3
"""Independent Bluetooth SPP <-> ROS1 FF-PD operator bridge.

This bridge does not publish /cmd_vel or implement safety logic.
Run alongside the team's *different* SPP profile (different UUID and channel).
Requires python3-dbus, python3-gi, BlueZ and ROS Noetic on the NUC.
"""
import argparse
import json
import logging
import math
import os
from pathlib import Path
import socket
import subprocess
import threading
import time

ROUTES = ("A_to_D4", "A_to_D5", "D4_to_A", "D4_to_D5", "D5_to_A", "D5_to_D4")
PID_SPP_UUID = "9a1f17cc-18d2-45f0-b6e7-1b0e257a902e"
PID_SPP_CHANNEL = 7
LOG = logging.getLogger("pid_bt_bridge")


class Bridge:
    def __init__(self, scripts, routes_dir):
        self.scripts = Path(scripts).expanduser().resolve()
        self.routes_dir = Path(routes_dir).expanduser().resolve()
        self.lock = threading.RLock()
        self.selected_route = "A_to_D5"
        self.active_route = None
        self.estop_latched = False
        self.jobs = {}
        self.job_status = {"name": None, "state": "IDLE", "detail": "", "tail": []}
        self.ready_state = "UNKNOWN"
        self.latest = {}
        self.ros_connected = False
        self.rospy = None

    def _script(self, filename):
        path = self.scripts / filename
        if not path.is_file():
            raise RuntimeError("Missing NUC PID script: " + str(path))
        return str(path)

    def _record_line(self, name, line):
        line = line.rstrip()[:240]
        with self.lock:
            if self.job_status["name"] == name:
                tail = self.job_status["tail"]
                tail.append(line)
                del tail[:-12]
                self.job_status["detail"] = line
        if line:
            LOG.info("%s: %s", name, line)

    def _run_job(self, name, args, long_lived=False, end_callback=None):
        with self.lock:
            if name in self.jobs and self.jobs[name].poll() is None:
                raise RuntimeError(name + " is already running")
            cmd = ["/bin/bash", self._script(args[0])] + args[1:]
            # Separate process group: Bluetooth session loss MUST NOT kill DRIVE.
            proc = subprocess.Popen(
                cmd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT, text=True, bufsize=1,
                start_new_session=True)
            self.jobs[name] = proc
            self.job_status = {"name": name, "state": "RUNNING",
                               "detail": " ".join(args), "tail": []}

        def follow():
            try:
                for line in proc.stdout:
                    self._record_line(name, line)
                code = proc.wait()
            except Exception as exc:
                code = -1
                LOG.exception("job stream error: %s", exc)
            finally:
                if proc.stdout:
                    proc.stdout.close()
            with self.lock:
                if self.jobs.get(name) is proc:
                    self.jobs.pop(name, None)
                if self.job_status["name"] == name:
                    self.job_status["state"] = "SUCCEEDED" if code == 0 else "FAILED"
                    self.job_status["detail"] += " [exit %s]" % code
                if name == "ready_start":
                    self.ready_state = "READY" if code == 0 else "FAILED"
                elif name == "ready_stop" and code == 0:
                    self.ready_state = "STOPPED"
                elif name == "drive" and self.active_route is not None:
                    self.active_route = None
            if end_callback:
                end_callback(code)
            LOG.info("%s exited %s", name, code)

        threading.Thread(target=follow, daemon=True, name="job_" + name).start()
        return {"ok": True, "detail": name + " started"}

    def _running(self, name):
        p = self.jobs.get(name)
        return p is not None and p.poll() is None

    def _route_frame(self):
        with self.lock:
            name = self.selected_route
        path = self.routes_dir / (name + "_waypoints.json")
        if not path.is_file():
            raise RuntimeError("Route file not found: " + str(path))
        with path.open(encoding="utf-8") as f:
            data = json.load(f)
        if data.get("frame") != "map" or data.get("route_key") != name:
            raise RuntimeError("Wrong route or map frame: " + str(path))
        pts = data["waypoints"]
        stride = max(1, math.ceil(len(pts) / 400))
        return {"type": "route", "route": name, "frame": "map",
                "count_full": len(pts), "stride": stride,
                "points": [{"x": p["x"], "y": p["y"]}
                           for p in pts[::stride]]}

    def handle(self, payload):
        """Return an ACK and optional route frame. No arbitrary shell commands."""
        command = payload.get("command") or payload.get("action")
        response = {"type": "ack", "command": command, "ok": False, "detail": ""}
        extra = None
        try:
            if command == "ping":
                response.update(ok=True, detail="pong")
            elif command in ("route_select", "select_route"):
                name = payload.get("route")
                if name not in ROUTES:
                    raise ValueError("Invalid route")
                with self.lock:
                    if self._running("drive"):
                        raise RuntimeError("Cannot change route while DRIVE is running")
                    self.selected_route = name
                extra = self._route_frame()
                response.update(ok=True, detail="route selected: " + name)
            elif command == "route":
                extra = self._route_frame()
                response.update(ok=True, detail="route loaded")
            elif command in ("ready_start", "stack_start"):
                with self.lock:
                    if self._running("ready_start"):
                        raise RuntimeError("READY already starting")
                    if self._running("drive"):
                        raise RuntimeError("DRIVE is active")
                    self.ready_state = "STARTING"
                response.update(self._run_job("ready_start",
                                             ["pid_ready.sh", "start"]))
            elif command == "ready_status":
                response.update(self._run_job("ready_status",
                                             ["pid_ready.sh", "status"]))
            elif command == "ready_stop":
                with self.lock:
                    if self._running("drive"):
                        raise RuntimeError("Stop DRIVE first")
                response.update(self._run_job("ready_stop",
                                             ["pid_ready.sh", "stop"]))
            elif command == "drive_start":
                name = payload.get("route", self.selected_route)
                if name not in ROUTES:
                    raise ValueError("Invalid route")
                with self.lock:
                    if self.estop_latched:
                        raise RuntimeError("E-STOP latched; release separately")
                    if self._running("drive") or self._running("drive_stop"):
                        raise RuntimeError("DRIVE already running or stopping")
                    if self._running("ready_start"):
                        raise RuntimeError("READY still starting")
                    self.selected_route = name
                # Check matching map route asset before attempting to move.
                extra = self._route_frame()
                response.update(self._run_job("drive", ["pid_drive.sh", name],
                                              long_lived=True))
                with self.lock:
                    self.active_route = name
            elif command in ("drive_stop", "stop", "estop"):
                if command == "estop":
                    with self.lock:
                        self.estop_latched = True
                # pid_stop.sh handles /ff_pid/start false, MANUAL, wheel verification.
                response.update(self._run_job("drive_stop", ["pid_stop.sh"]))
            elif command == "estop_release":
                with self.lock:
                    self.estop_latched = False
                response.update(ok=True, detail="Software E-STOP released; DRIVE not started")
            else:
                response["detail"] = "Unknown command"
        except Exception as exc:
            response["detail"] = str(exc)
            LOG.warning("Rejected %s: %s", command, exc)
        return response, extra

    def _store(self, key, data):
        with self.lock:
            self.latest[key] = (time.monotonic(), data)

    def _json_cb(self, key, msg):
        try:
            value = json.loads(msg.data)
            if isinstance(value, dict):
                self._store(key, value)
        except (ValueError, TypeError):
            LOG.warning("Invalid %s JSON", key)

    def _pose_cb(self, msg):
        p, q = msg.pose.pose.position, msg.pose.pose.orientation
        yaw = math.degrees(math.atan2(
            2 * (q.w * q.z + q.x * q.y),
            1 - 2 * (q.y * q.y + q.z * q.z)))
        self._store("pose", {"x": p.x, "y": p.y, "yaw_deg": yaw,
                             "source": "GICP", "frame": msg.header.frame_id})

    def _wheel_cb(self, msg):
        mode = int(msg.data[1]) if len(msg.data) > 1 else None
        self._store("wheel", {"mode": mode,
                              "mode_name": {65: "AUTO", 77: "MANUAL"}.get(mode, "UNKNOWN")})

    def _diag_cb(self, msg):
        for d in msg.status:
            if d.message:
                self._store("gicp", {"state": d.message,
                                      "level": d.level})
                break

    def attach_ros_when_available(self):
        """Bluetooth works before roscore: subscribe after READY starts it."""
        try:
            import rospy
            from std_msgs.msg import String, Int16MultiArray
            from geometry_msgs.msg import PoseWithCovarianceStamped
            from diagnostic_msgs.msg import DiagnosticArray
        except ImportError as exc:
            LOG.error("ROS Noetic imports missing: %s", exc)
            return

        while True:
            try:
                uri = os.environ.get("ROS_MASTER_URI", "http://127.0.0.1:11311")
                from urllib.parse import urlparse
                dest = urlparse(uri)
                with socket.create_connection((dest.hostname, dest.port or 11311), timeout=1):
                    pass
                rospy.init_node("pid_bt_bridge", anonymous=False, disable_signals=True)
                rospy.Subscriber("/pid_safety/status", String,
                                 lambda msg: self._json_cb("safety", msg), queue_size=2)
                rospy.Subscriber("/ff_pid/status", String,
                                 lambda msg: self._json_cb("drive", msg), queue_size=2)
                rospy.Subscriber("/fast_lio_icp/pose", PoseWithCovarianceStamped,
                                 self._pose_cb, queue_size=2)
                rospy.Subscriber("/wheel_status", Int16MultiArray,
                                 self._wheel_cb, queue_size=2)
                rospy.Subscriber("/fast_lio_icp/localization_diagnostics", DiagnosticArray,
                                 self._diag_cb, queue_size=2)
                self.rospy = rospy
                self.ros_connected = True
                LOG.info("ROS1 subscriptions attached")
                return
            except Exception as exc:
                LOG.info("ROS not yet online: %s", exc)
                time.sleep(3)

    def telemetry(self):
        now = time.monotonic()
        with self.lock:
            def fresh(key, ttl):
                value = self.latest.get(key)
                return value[1] if value and now - value[0] <= ttl else None

            safety = fresh("safety", 1.5)
            drive = fresh("drive", 1.5)
            pose = fresh("pose", 1.5)
            wheel = fresh("wheel", 1.5)
            gicp = fresh("gicp", 5.0)
            route = self.active_route
            if drive and isinstance(drive.get("pose_used"), (tuple, list)) and len(drive["pose_used"]) == 3:
                x, y, yaw = drive["pose_used"]
                pose = {"x": x, "y": y, "yaw_deg": math.degrees(yaw),
                        "source": "FF_PID", "frame": "map"}
            return {
                "type": "telemetry", "protocol_version": 4, "stamp": time.time(),
                "ros_connected": self.ros_connected,
                "ready": {"state": self.ready_state, "gicp": gicp,
                          "job": dict(self.job_status)},
                "selected_route": self.selected_route,
                "active_route": route,
                "drive_state": (drive or {}).get("state", "NOT_RUNNING"),
                "drive": drive,
                "safety": safety,
                "pose": pose, "wheel": wheel,
                "estop_latched": self.estop_latched,
            }


class Session:
    def __init__(self, sock, bridge):
        self.sock = sock
        self.bridge = bridge
        self.write_lock = threading.Lock()
        self.alive = threading.Event()
        self.alive.set()

    def send(self, payload):
        try:
            data = (json.dumps(payload, ensure_ascii=False,
                               separators=(",", ":"), allow_nan=False) + "\n").encode("utf-8")
            with self.write_lock:
                self.sock.sendall(data)
            return True
        except (OSError, ValueError, TypeError):
            self.alive.clear()
            return False

    def serve(self):
        def broadcast():
            while self.alive.is_set():
                if not self.send(self.bridge.telemetry()):
                    return
                self.alive.wait(0.0)
                time.sleep(0.5)

        thread = threading.Thread(target=broadcast, daemon=True)
        thread.start()
        buffer = b""
        try:
            while self.alive.is_set():
                chunk = self.sock.recv(4096)
                if not chunk:
                    break
                buffer += chunk
                if len(buffer) > 65536:
                    buffer = b""
                    continue
                while b"\n" in buffer:
                    raw, buffer = buffer.split(b"\n", 1)
                    try:
                        request = json.loads(raw.decode("utf-8"))
                        if not isinstance(request, dict):
                            raise ValueError("Command must be a JSON object")
                        reply, extra = self.bridge.handle(request)
                    except (ValueError, UnicodeError) as exc:
                        reply = {"type": "ack", "ok": False, "detail": str(exc)}
                        extra = None
                    self.send(reply)
                    if extra:
                        self.send(extra)
        except OSError as exc:
            LOG.info("Bluetooth session closed: %s", exc)
        finally:
            self.alive.clear()
            # Never terminate DRIVE/READY jobs on Bluetooth disconnection.
            try:
                self.sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            thread.join(timeout=2)


def serve_bluez(bridge, uuid, channel):
    import dbus
    import dbus.mainloop.glib
    import dbus.service
    from gi.repository import GLib
    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
    bus = dbus.SystemBus()
    path = "/uniconlab/wheelchair/pid_spp"

    class Profile(dbus.service.Object):
        @dbus.service.method("org.bluez.Profile1", in_signature="", out_signature="")
        def Release(self):
            LOG.warning("Bluetooth profile released")

        @dbus.service.method("org.bluez.Profile1", in_signature="oha{sv}", out_signature="")
        def NewConnection(self, device, fd, properties):
            raw = fd.take()
            client = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM,
                                   socket.BTPROTO_RFCOMM, fileno=raw)
            client.setblocking(True)
            LOG.info("Android connected: %s", device)
            def run():
                try:
                    Session(client, bridge).serve()
                finally:
                    client.close()
                    LOG.info("Android disconnected: %s", device)
            threading.Thread(target=run, daemon=True).start()

        @dbus.service.method("org.bluez.Profile1", in_signature="o", out_signature="")
        def RequestDisconnection(self, device):
            LOG.info("Disconnect request: %s", device)

    obj = Profile(bus, path)
    manager = dbus.Interface(bus.get_object("org.bluez", "/org/bluez"),
                             "org.bluez.ProfileManager1")
    options = {"Name": "UNICON FF-PID Operator",
               "Role": "server", "Channel": dbus.UInt16(channel),
               "RequireAuthentication": dbus.Boolean(True),
               "RequireAuthorization": dbus.Boolean(False),
               "AutoConnect": dbus.Boolean(False)}
    # No fallback to default SPP: that could hijack the team's Bluetooth profile.
    manager.RegisterProfile(path, uuid.lower(), options)
    LOG.info("PID RFCOMM registered: UUID %s, channel %s", uuid, channel)
    loop = GLib.MainLoop()
    try:
        loop.run()
    except KeyboardInterrupt:
        pass
    finally:
        manager.UnregisterProfile(path)
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--pid-scripts", default=os.environ.get(
        "PID_SCRIPTS_DIR", "~/wheelchair_localization_src/src/pid/scripts"))
    ap.add_argument("--routes-dir", default=None)
    ap.add_argument("--uuid", default=PID_SPP_UUID)
    ap.add_argument("--channel", type=int, default=PID_SPP_CHANNEL)
    args = ap.parse_args()
    if not 1 <= args.channel <= 30:
        ap.error("RFCOMM channel must be in 1..30")
    scripts = Path(args.pid_scripts).expanduser().resolve()
    routes = args.routes_dir or str(scripts.parents[2] / "routes" / "route2_manual_20261031")
    logging.basicConfig(level=logging.INFO, format="%(asctime)s [pid_bt] %(message)s")
    bridge = Bridge(scripts, routes)
    threading.Thread(target=bridge.attach_ros_when_available, daemon=True).start()
    return serve_bluez(bridge, args.uuid, args.channel)


if __name__ == "__main__":
    raise SystemExit(main())
