# FF–PID 전용 NUC Bluetooth Bridge

기존 팀 `ros1_bluetooth_bridge.py`는 **변경하거나 종료하지 않습니다**. 본 앱의 `nuc/pid_bt_bridge.py`는 별도 BlueZ SPP 프로필로 실행하는 독립 브릿지입니다.

- UUID: `9a1f17cc-18d2-45f0-b6e7-1b0e257a902e`
- RFCOMM 채널 기본값: `7` (NUC에서 해당 채널이 비어 있는지 확인)
- BlueZ D-Bus 오브젝트 경로: `/uniconlab/wheelchair/pid_spp`
- Android: `BluetoothWheelchairClient.java`에서 위 전용 UUID 사용
- 메시지: 줄바꿈으로 구분한 JSON, 주기적 `type=telemetry` / `type=ack` / `type=route`
- 제어 입력은 **6개 경로 명칭만 허용**하며, `--test-clear` 등 우회 옵션을 받지 않습니다.

## NUC 수동 기동 (최초 확인용)

아래 경로는 **예시**입니다. 실제 NUC에 있는 팀 작업 공간/배포 경로를 지정해야 합니다.

```bash
source /opt/ros/noetic/setup.bash
source /path/to/ROS_workspace/devel/setup.bash

python3 nuc/pid_bt_bridge.py \
  --pid-scripts /path/to/team_workspace/src/pid/scripts \
  --routes-dir /path/to/team_workspace/routes/route2_manual_20261031 \
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
| `{"command":"estop"}` | 소프트웨어 정지 요청 후 재출발 금지 래치 |
| `{"command":"estop_release"}` | 앱 측 소프트웨어 래치만 해제하며 주행 재개는 하지 않음 |
| `{"command":"route"}` | 현재 선택 경로 지도 전송 |
| `{"command":"ping"}` | 링크 확인 |

`drive_start`가 시작하는 `pid_drive.sh`는 **장시간 유지**되며, Bluetooth 세션이 끊어져도 프로세스를 종료하지 않습니다. PID 주행 종료/정지는 기존 스크립트에서 처리합니다. 새 브릿지는 `/cmd_vel`을 발행하지 않습니다.

## 수신 텔레메트리

`protocol_version=4`, `ready`(작업 상태·최근 로그), `selected_route`, `active_route`, `drive_state`, `drive`, `safety`(상태·이유), `pose`(현재 위치·방향), `wheel`을 2 Hz로 전송합니다. ROS 토픽은 최신값에 유효시간을 적용해 오래된 데이터를 사용하지 않습니다.

- `/pid_safety/status`
- `/ff_pid/status`
- `/fast_lio_icp/pose`
- `/wheel_status`
- `/fast_lio_icp/localization_diagnostics`

**현재 단계:** 브릿지의 최초 소스와 Android 전용 UUID를 저장소에 반영했습니다. 이 프로토콜에 맞춘 Android 두 화면, READY 상세 모니터링 UI, 실제 NUC 동작 검증 및 systemd 등록은 아직 완료되지 않았습니다.

## 원본 코드

- 팀 Bluetooth Bridge: https://github.com/Dannythechampion/UNICON_wheelchair_v2/blob/main/scripts/ros1_bluetooth_bridge.py
- PID/Safety: https://github.com/Iamshy95/UNICON_wheelchair_PID

앱 전체의 원저작자/출처는 루트 `README.md`, `NOTICE.md`, `LICENSE`에 명시되어 있습니다.
