# FF–PID 전용 NUC Bluetooth Bridge

기존 팀 `ros1_bluetooth_bridge.py`는 **변경하거나 종료하지 않습니다**. 본 앱의 `nuc/pid_bt_bridge.py`는 별도 BlueZ SPP 프로필로 실행하는 독립 브릿지입니다.

- UUID: `9a1f17cc-18d2-45f0-b6e7-1b0e257a902e`
- RFCOMM 채널 기본값: `7` (NUC에서 해당 채널이 비어 있는지 확인)
- BlueZ D-Bus 오브젝트 경로: `/uniconlab/wheelchair/pid_spp`
- Android: `BluetoothWheelchairClient.java`에서 위 전용 UUID 사용
- 메시지: 줄바꿈으로 구분한 JSON, 주기적 `type=telemetry` / `type=ack` / `type=route`
- 제어 입력은 **6개 경로 명칭만 허용**하며, `--test-clear` 등 우회 옵션을 받지 않습니다.

## NUC 수동 기동 (최초 확인용)

아래 경로는 **예시**입니다. 실제 NUC에 있는 팀 작업 공간/배포 경로를 지정해야 합니다. `--pid-scripts`가 `source-linux/src/pid/scripts`이면 기본 `--routes-dir`는 `source-linux/routes/route2_manual_20261031`로 계산되며, `pid_drive_check.py` 및 FF–PID가 사용하는 경로와 동일합니다. 별도로 `--routes-dir`를 지정할 때도 이 동일한 경로를 사용하세요.

```bash
source /opt/ros/noetic/setup.bash
source /path/to/ROS_workspace/devel/setup.bash

python3 nuc/pid_bt_bridge.py \
  --pid-scripts /path/to/team_workspace/src/pid/scripts \
  --routes-dir /path/to/source-linux/routes/route2_manual_20261031 \
  --channel 7
```

NUC OS에 `python3-dbus`, `python3-gi`와 BlueZ가 설치되어 있어야 합니다. 기존 브릿지와 같은 시스템 Python/ROS 환경에서 실행합니다.

**중요:** 이 파일만 GitHub에 추가해도 NUC에서 실행되거나 자동 기동되는 것은 아닙니다. NUC에 해당 파일을 배포하고 기존 팀 서비스와 별도로 systemd 자동 기동 서비스를 등록해야 합니다. 두 서비스가 같은 UUID/채널을 사용하지 않도록 확인합니다.

## 앱 명령 (1행 1 JSON)

| 명령 | 동작 |
| --- | --- |
| `{"command":"ready_start"}` | `pid_ready.sh start` 실행 |
| `{"command":"ready_status"}` | `pid_ready.sh status` 상태 캡처 |
| `{"command":"route_select","route":"A_to_D5"}` | 6개 경로 중 선택 및 경로 JSON 전송 |
| `{"command":"drive_start","route":"A_to_D5"}` | `pid_drive.sh A_to_D5` 비동기 실행 |
| `{"command":"drive_stop"}` | `pid_stop.sh` 실행 |
| `{"command":"drive_reconcile"}` | 앱 전용 세션 상태 복구: 주행 프로세스 없음 + ROS 노드 없음 + MANUAL/양쪽 바퀴 정지 3회 검증 후에만 STOPPED로 전이 (명령 전송만으로 정지했다고 판단하지 않음) |
| `{"command":"estop"}` | 소프트웨어 정지 요청 후 재출발 금지 래치 |
| `{"command":"estop_release"}` | 앱 측 소프트웨어 래치만 해제하며 주행 재개는 하지 않음 |
| `{"command":"route"}` | 현재 선택 경로 좌표(JSON waypoints, 지도 이미지 아님) 전송 |
| `{"command":"status"}` | 현재 통합 상태 재전송 |
| `{"command":"ping"}` | 링크 확인 |

`drive_start`가 시작하는 `pid_drive.sh`는 **장시간 유지**되며, Bluetooth 세션이 끊어져도 프로세스를 종료하지 않습니다. PID 주행 종료/정지는 기존 스크립트에서 처리합니다. 새 브릿지는 `/cmd_vel`을 발행하지 않습니다.

## 출발 검사 / 주행 상태 이벤트

2026-11-09 PID `main` 병합 버전(`1305a56`)의 실제 표준 출력을 그대로 파싱합니다.

- `drive_preflight`: 정차 위치·방향 1.0m / 30° 검사 결과. `ok=false`일 때 `turn_direction`, `approach_m`, `guidance` 등이 포함됩니다.
- `drive_event`: `STARTED/START_CONFIRMED`는 셸 실행·ROS START 서비스 승인입니다. 앱은 이것만으로 실제 주행 성공으로 판단하지 않습니다.
- `drive_result`: 브릿지가 발행하며 `status=STARTED, reason=ROS_RUNNING`일 때 해당 경로의 **실제 /ff_pid/status RUNNING**이 확인된 것입니다. `status=REJECTED`는 시작 거부, `STOPPED`는 pid_stop.sh 정차 확인, `STOP_UNVERIFIED`는 정차 확인 실패를 뜻합니다.
- `job_result`: READY/DRIVE 시작·종료 등 백그라운드 작업의 결과/종료 코드.
- `type=ack, ok=true`: **명령 접수**를 뜻하며, READY 완료·모터 주행·정지 성공을 뜻하지 않습니다.

연결되어 있는 동안 이벤트는 순서번호 `seq`와 함께 전송됩니다. 재연결 후에는 명령을 임의로 재실행하지 않고 주기적인 `telemetry`에서 마지막 이벤트와 실제 토픽 상태를 다시 확인합니다. 브릿지 종료 또는 NUC 재부팅 이후의 주행 상태 복원과 실기 테스트는 아직 검증되지 않았습니다.

## 휠체어 중심 위치 보정

`/fast_lio_icp/pose`는 GICP/FAST-LIO 센서의 차체 기준 위치로 취급하고, 브릿지에서 FF–PID의 `pid_drive_check.py`와 동일하게 `static_livox_localization/scripts/body_frame.py`의 `pose_correction("builtin", route_profile) @ reference_correction("chair_centre", route_chair_centre(metadata))`를 적용합니다. 경로별 `chair_centre_in_body_xyz` 메타데이터를 사용하므로 주행 전·중·후 모두 같은 중심점의 XY/Yaw를 표시합니다. `pose_used`(제어 예측 자세)로 앱 표시 원점을 임의로 전환하지 않습니다. 변환 모듈이나 메타데이터가 없으면 **원본 GICP 좌표로 대신 표시하지 않고** 위치를 미수신 상태로 둡니다. 좌표계 및 실제 장착 오프셋 검증은 NUC 통합 테스트에서 진행해야 합니다.

## 수신 텔레메트리

`protocol_version=4`, `ready`(작업 상태·최근 로그), `selected_route`, `active_route`, `drive_state`, `drive`, `safety`(상태·이유), `pose`(보정된 휠체어 중심 위치·방향), `wheel`을 2 Hz로 전송합니다. ROS 토픽은 최신값에 유효시간을 적용해 오래된 데이터를 사용하지 않습니다.

- `/pid_safety/status`
- `/ff_pid/status`
- `/fast_lio_icp/pose`
- `/wheel_status`
- `/fast_lio_icp/localization_diagnostics`

**현재 단계 (2026-11-09):** Android 관리자/탑승자 두 화면은 작성되었으며 사용자가 에뮬레이터 실행을 확인했습니다. 탑승자 화면은 **임시 1단계(배경 지도 없이 경로 선 + 현재 위치/방향)** 까지 작성했습니다. **최종 단계에서는 기존 PCD를 가공한 실제 2D 배경지도와 건물·통로·A/D4/D5 명칭을 추가할 계획**입니다. 이는 현재 브릿지의 경로 전송·위치 텔레메트리 구조와 별도로 진행합니다. 브릿지는 실제 NUC 제어기와 같은 `source-linux/routes/route2_manual_20261031/` 경로 JSON을 읽도록 기본 설정했습니다. **NUC 배포, 실제 Bluetooth 연결, READY·경로·Safety 텔레메트리, 주행 종료, 시스템 진단의 실기 통합 검증 및 systemd 등록은 아직 완료되지 않았습니다.**

## 원본 코드

- 팀 Bluetooth Bridge: https://github.com/Dannythechampion/UNICON_wheelchair_v2/blob/main/scripts/ros1_bluetooth_bridge.py
- PID/Safety: https://github.com/Iamshy95/UNICON_wheelchair_PID

앱 전체의 원저작자/출처는 루트 `README.md`, `NOTICE.md`, `LICENSE`에 명시되어 있습니다.


## 2026-11-13 앱 종료 실패 복구 패치 (검증 전)

- 이 변경은 독립 브릿지 `pid_bt_bridge.py`, 같은 디렉터리의 **새로운** `pid_drive_reconcile.sh` 및 Android 앱을 **함께 배포**해야 작동합니다. 기존 FF-PID/Safety/ROS 주행 스크립트는 수정하지 않습니다.
- `drive_reconcile`은 모터에 제어 명령을 내리지 않고, 이미 배포된 `pid_drive_check.py`로 `MANUAL + 양쪽 바퀴 정지` 피드백을 확인하고 /ff_pid, /pid_safety, /pid_astar_planner ROS 노드와 주행/종료 프로세스가 모두 없을 때만 브릿지의 오래된 상태를 지웁니다.
- 검증에 실패하면 상태를 지우지 않습니다. 앱은 정확한 거부 사유를 유지해 표시하며, `STOPPING`에 갇혀도 일정 시간 경과 후 종료를 **재시도**할 수 있습니다(정지 완료로 간주하지 않음).
- 실제 앱/NUC 배포 전에는 **무인·바퀴를 지면에서 분리한 시험 장치** 등 안전 확보된 환경에서 `STOP_UNVERIFIED`, 신호 끊김, 동시 STOP, 종료 중 수동 이동을 확인해야 합니다. 어떠한 경우에도 물리적 E-stop을 대체하지 않습니다.
- NUC에 기존 서비스가 실행 중이면 소스만 복사해도 반영되지 않습니다. 재시작은 실제 주행이 없고 완전히 정지된 것을 확인한 뒤에만 실시합니다. 기존 운영 배포 파일과 업데이트 파일을 먼저 비교하고 백업합니다.

## 실내 오프라인 테스트 (운용 NUC와 분리)

저장소 복사본에서 `python3 -m unittest discover -s nuc/tests -p 'test_*.py' -v` 를 실행하면 브릿지의 오래된 상태 차단, 종료 재시도 허용, 재검증 요청, 동시 작업 거부를 **실제 ROS/블루투스/구동 없이** 시험할 수 있습니다. 테스트는 `_run_job`을 대체하므로 실제 `pid_drive.sh`나 `pid_stop.sh`를 실행하지 않습니다. 실기 정차 검증과는 별개입니다.

운영 Android 앱의 실내 모의 테스트 화면과 버튼은 제거했습니다. Android 모의 상태 전이 검사는 `app/src/test/`의 단위 테스트 코드에서만 실행되며, APK나 실제 Bluetooth 제어 경로에 포함되지 않습니다. 실제 Bluetooth 상태 복구 동작을 확인하려면 NUC 브릿지 배포 후 실내 통합 시험이 필요합니다.
