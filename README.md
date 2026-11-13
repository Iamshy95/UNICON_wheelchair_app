> **출처 및 저작권 (Source & Attribution)**
>
> - **기반이 된 원본 Android 앱:** https://github.com/gyeong-yeon2718/uniconlab-wheelchair-operator-app
> - **최초 원본 프로젝트:** https://github.com/Geppetto0608/edge-mobility-monitor
> - **원저작자:** 박형준 (Park Hyeongjun), © 2026. All rights reserved.
> - 원저작자의 허락을 받아 제공된 코드를 바탕으로 개발했습니다. 사용·재배포 조건은 [NOTICE.md](NOTICE.md), [LICENSE](LICENSE)를 참고하세요. 공개 저장소라고 해서 제3자에게 별도 이용 권한이 부여되는 것은 아닙니다.

# UNICON FF–PID 휠체어 Android 앱

한양대학교 UNICON Lab의 **ROS1 Noetic FF–PID / Safety 기반 전동휠체어**를 운용하는 전용 Android 앱과 NUC Bluetooth 브릿지입니다. 이 저장소는 개인 FF–PID용으로 정리했으며 기존 팀 앱과 팀 Bluetooth 브릿지는 별도로 유지합니다.

## 지도 개발 계획: 1단계 경로 표시 → 2단계 PCD 기반 2D 배경지도

**현재는 임시 1단계(기존 2안)만 구현했습니다.** 탑승자 화면에는 선택한 경로 선, 출발·도착 지점, 실시간 휠체어 위치와 방향 화살표를 표시합니다. **최종 앱에는 PCD 기반 2D 배경지도를 반드시 추가할 계획입니다.**

- 경로: NUC가 실제 주행에 사용하는 6개 JSON 경로 중 선택한 경로를 Bluetooth로 받습니다. 전송 지점을 줄이더라도 실제 FF–PID 주행 경로는 변경되지 않습니다.
- 위치/방향: NUC 브릿지가 FF–PID와 같은 `body_frame.py`·경로 JSON의 `chair_centre_in_body_xyz`를 적용해 **주행 전·중·후 동일한 휠체어 중심 기준**의 ROS `map` 좌표를 전송합니다. 원본 GICP 좌표를 중심 좌표로 잘못 표시하지 않으며, 보정 실패·통신 끊김·정보 만료 시 위치 표시를 숨깁니다.
- **다음 지도 개발 단계 (미구현·필수 예정):** NUC에 이미 존재하는 PCD 맵을 적절한 높이 필터링·격자화·노이즈 정리로 2D PNG로 만들고, ROS `map` 좌표계에 맞춰 정적 배경으로 앱에 추가합니다. 겹치는 점을 단순 투영하는 방식이나 주행 가능 영역 마스크를 배경지도로 사용하는 방식은 피합니다. 건물·통로를 알아보기 쉽게 정리하고 A·D4·D5 표기를 추가합니다. 경로 및 실시간 위치는 PNG 위에 별도 오버레이합니다.
- Bluetooth에 연결하지 않은 에뮬레이터에서는 경로가 실제로 수신되지 않아 ‘경로 데이터 대기 중’이 정상입니다.

## 탑승자 경로 명칭

탑승자 화면의 장소 표기는 `A = 애지문(2번 출구)`, `D4 = 정몽구센터`, `D5 = 2공학관`입니다. 경로 선택 목록과 선택 경로 안내, 지도 제목, 출발·도착 지점 표기에 적용합니다. ROS·NUC에 전송하는 `A_to_D5` 등 6개 내부 경로 ID와 실제 Waypoint 데이터는 그대로 유지합니다.

## 화면 기능

| 관리자용 | 탑승자용 |
| --- | --- |
| Bluetooth **연결 버튼** 및 연결 상태 | Bluetooth 연결 상태 |
| READY 시작 | A / D4 / D5 간 **6개 경로 선택** |
| READY 종료 (확인창) | 주행 시작 / 주행 종료 |
| READY 진행 상태 및 센서·FAST-LIO·GICP·인지·휠체어·UART 진단 | 출발 위치·방향 조정 안내 및 실패 사유 |
| 작업 로그·실패 이유 | Safety CLEAR / SLOW / STOP 및 이유 |
| | 경로 선, 출발/도착, 실시간 위치·방향 |

Bluetooth 기기 선택·연결 해제 버튼, 앱 비상정지 버튼, 카메라, 로그인, Wi-Fi 기능, 기존 프리셋은 사용하지 않습니다.

- **READY 시작/종료:** NUC의 `pid_ready.sh start|stop`를 사용합니다.
- **주행 시작:** `pid_drive.sh <경로>`에서 START를 누른 시점에 경로까지의 거리 1.0m / 방향 30° 사전 검사를 실시합니다. 서비스 ACK만으로 성공으로 표시하지 않고 실제 ROS RUNNING 상태 확인을 별도로 처리합니다.
- **주행 종료:** `pid_stop.sh`로 정지하며 READY는 유지합니다.
- **Safety STOP:** 장애물 등으로 일시 정지된 상태이며, 앱의 ‘주행 종료’와는 다릅니다.
- **긴급 정지:** 앱 버튼 대신 기존 물리 조이스틱 측 정지 수단을 사용합니다. Bluetooth 단절 시 앱으로 정지를 요청할 수 없으므로 통신 장애를 안전 상태로 오인하지 않아야 합니다.

## 구성

```text
스마트폰 Android 앱
  PidOperatorActivity + PidRouteMapView
              │
              │ Bluetooth Classic RFCOMM
              │ UUID: 9a1f17cc-18d2-45f0-b6e7-1b0e257a902e
              ▼
NUC: nuc/pid_bt_bridge.py
     ├─ pid_ready.sh
     ├─ pid_drive.sh / pid_stop.sh
     ├─ 선택 경로 JSON
     └─ ROS 위치·Safety·READY 텔레메트리
              ▼
      기존 ROS1 FF–PID / Safety
```

기존 팀의 `ros1_bluetooth_bridge.py`는 **변경하거나 종료하지 않습니다.** 개인 브릿지는 전용 UUID/채널(기본 7)을 사용하며 `/cmd_vel`을 직접 발행하지 않습니다.

### 경로 디렉터리 (NUC)

개인 PID GitHub의 `waypoints/`는 파일 보관 위치이지만, 실제 NUC의 FF–PID 제어기와 사전검사는 다음 폴더를 참조합니다.

```text
source-linux/routes/route2_manual_20261031/
  A_to_D4_waypoints.json
  A_to_D5_waypoints.json
  D4_to_A_waypoints.json
  D4_to_D5_waypoints.json
  D5_to_A_waypoints.json
  D5_to_D4_waypoints.json
```

브릿지에서 `--pid-scripts`가 `<REPO_ROOT>/src/pid/scripts`라면 기본 `--routes-dir`도 `<REPO_ROOT>/routes/route2_manual_20261031`입니다. 이는 `scripts.parents[2]`로 계산되며, 실제 제어기와 **같은 경로 파일을 읽기 위해 설정**했습니다. 명령줄에서 `--routes-dir`를 지정하면 그 값이 우선하므로 동일한 폴더를 지정해야 합니다. 실제 NUC의 폴더 존재 여부는 배포 시 확인합니다.

## 주요 코드

| 경로 | 역할 |
| --- | --- |
| `app/src/main/java/com/example/jetsonbtmonitor/PidOperatorActivity.java` | 관리자/탑승자 화면, 버튼, 상태 표시 |
| `app/src/main/java/com/example/jetsonbtmonitor/PidRouteMapView.java` | 배경 지도 없이 선택 경로와 위치·방향 표시 |
| `app/src/main/java/com/example/jetsonbtmonitor/BluetoothWheelchairClient.java` | Bluetooth 연결 및 자동 재연결 |
| `nuc/pid_bt_bridge.py` | ROS1 ↔ Bluetooth 메시지와 READY/DRIVE 명령 처리 |
| [`nuc/README.md`](nuc/README.md) | NUC 브릿지 실행·명령·텔레메트리 상세 |

Bluetooth 장치 이름은 페어링 목록에서 `nuc`, `wheelchair`, 또는 정확히 `mprp3`인 기기를 자동 인식합니다. 한 번 선택되면 MAC 주소를 저장해 이후 연결 시 우선 사용합니다. 별도의 기기 선택 화면은 없습니다.

## 앱 상태 처리 오프라인 단위 테스트 (운영 화면과 완전히 분리)

실내 모의 테스트 **화면과 버튼은 실제 Android 앱에서 제거했습니다.** 실차 운용 앱에는 관리자/탑승자 기존 화면과 `정지 상태 재검증` 버튼만 남습니다.

실제 Bluetooth, LiDAR, ROS, 모터와 관련 없는 상태 전이 검사는 Android 프로젝트의 `app/src/test/` 아래 `IndoorDriveSimulator.java`, `IndoorDriveSimulatorTest.java`에만 남겨두었습니다. 따라서 이 모의 엔진은 운영 APK에 들어가지 않으며, LiDAR가 없는 가짜 환경에서 눌러 오류가 나던 테스트 버튼도 없습니다.

오프라인 단위 테스트: `./gradlew :app:testDebugUnitTest`

**단위 테스트 통과는 실제 앱–NUC 통합 검증이 아닙니다.** NUC Bluetooth 브릿지를 수정 배포한 후, 실제 바퀴가 MANUAL/정지임을 확인한 환경에서 `정지 상태 재검증`이 정상적으로 동작하는지 별도로 시험해야 합니다.

## Android 빌드

기존 프로젝트 폴더에서 최신 코드를 받습니다.

```bash
cd ~/ws/wheelchair_ws/UNICON_wheelchair_app
git pull origin main
```

Android Studio에서 **Build > Make Project** 후 에뮬레이터 또는 USB 디버깅을 켠 스마트폰을 선택하여 **Run(▶)** 을 누릅니다.

- **사용자가 확인한 것:** Android 에뮬레이터에서 앱이 실행됨. 연결되지 않은 상태에서는 명령 버튼이 동작하지 않거나 상태를 수신하지 못하는 것이 정상입니다.
- **아직 확인하지 못한 것:** 실제 스마트폰↔NUC Bluetooth 연결, 경로 수신/지도 좌표 정합, READY/주행/Safety 동작.
- 앱에서 기기 선택을 하지 않으므로 스마트폰 Bluetooth 설정에서 NUC를 먼저 페어링해야 합니다. 이전에 저장된 NUC 주소가 없으면 페어링된 기기명으로 단일 후보를 찾습니다.

## 다음 작업 및 검증 체크리스트

**2D 지도 제작은 최종 앱 완성에 필요한 예정 작업입니다.** 다만 당장 NUC Bluetooth·READY·주행 기능을 검증하는 데 선행 조건은 아닙니다. 실제 남은 작업은 아래와 같습니다.

- [ ] **NUC 브릿지 배포:** 최신 `nuc/pid_bt_bridge.py` 설치, ROS1/BlueZ 의존성과 스크립트 경로 확인.
- [ ] **Bluetooth 충돌 없는 연결:** 기존 팀 브릿지는 그대로 둔 채 새 UUID/RFCOMM 채널에서 Android 앱과 통신 확인.
- [ ] **READY 확인:** 시작·종료, 구성요소 상태와 실패 사유, 실제 센서·GICP·인지 데이터 정상 수신 확인.
- [ ] **경로·위치 확인:** 6개 경로 선택/전송과 실시간 위치·방향, ROS map 좌표계 정합 확인.
- [ ] **안전 기능 우선 점검:** 출발 거부 시 위치 조정 안내, RUNNING 확인, `pid_stop.sh` 정차 확인, 통신 끊김 표시, 물리 조이스틱 정지 수단 확인.
- [ ] **통제된 주행 테스트:** 안전 확보 후 START/STOP과 Safety CLEAR/SLOW/STOP 동작 검증.
- [ ] **자동 실행 설정:** NUC 부팅 시 개인 Bluetooth 브릿지가 시작되도록 별도 systemd 서비스 설치(기존 팀 서비스와 충돌 방지).
- [ ] **최종 지도 제작 (예정·필수):** NUC의 기존 PCD에서 건물·통로 구조를 추출하고 2D PNG로 정리. 지도 원점·해상도·축 방향을 기록하여 실제 ROS `map` 좌표계의 6개 경로와 GICP 위치를 겹쳐 검증하고 Android에 탑재.

## 구현 및 검증 상태 (2026-11-09)

| 항목 | 상태 |
| --- | --- |
| 관리자/탑승자 UI | 코드 작성 완료, 에뮬레이터 실행 사용자 확인 |
| 경로 선 + 위치·방향 표시 | 코드 작성 완료, NUC 데이터 연동 미검증 |
| 출발 조건·READY·Safety 연동 | 코드 작성, 실기 미검증 |
| FF–PID와 브릿지의 경로 폴더 일치 | 기본 경로 코드 수정 완료, NUC 실제 배포 미확인 |
| NUC 전용 Bluetooth 서비스 설치 | 미완료/미확인 |
| systemd 자동 실행 | 미설정 |
| 2D 배경 지도 | 추후 반드시 제작 예정 (PCD 기반, 현재 미구현) |

**주의:** GitHub에 코드가 반영되거나 에뮬레이터에서 실행된 사실은 실제 휠체어의 제어·정지·Safety 정상 동작을 보증하지 않습니다.


## LiDAR 연결 테스트 및 관리자 오류 진단 (신규 작업 브랜치)

관리자 화면에 **테스트 시작 / 테스트 종료**와 ROS Master·Livox 드라이버·LiDAR·IMU 상태, LiDAR·IMU 수신 끊김 횟수를 표시합니다. 테스트는 사용자가 종료할 때까지 데이터를 감시합니다. 정상 수신 여부가 바뀌면 화면도 갱신되며, 안내 문구는 **「테스트 종료 후 READY를 실행하세요.」**입니다.

NUC 브릿지의 명령 `lidar_test_start`, `lidar_test_stop`은 PID 저장소 신규 `scripts/pid_lidar_test.sh`를 호출합니다. 테스트는 READY·DRIVE와 중복 실행할 수 없습니다. 테스트에서 실행한 ROS Master 및 드라이버는 테스트 종료 시 함께 종료합니다. 다른 작업이 이미 사용 중인 Master를 임의로 종료하지 않습니다. READY에서 실패한 `[FAIL]` 및 `[ERROR]` 행을 모두 수집하고, 마지막 로그 한 줄 대신 실패 목록과 해당 실행의 전체 기록(최대 최근 100줄)을 별도로 보여줍니다. 성공한 READY의 이전 실패 메시지는 현재 실패 상태로 표시하지 않습니다.

탑승자 경로 선택 글자색·목록 대비를 명시하고, 최신 휠 상태에 따른 **수동 조작 모드 / 자율주행 모드 / 확인 불가** 표시를 추가했습니다. 휠 모드가 MANUAL이라고 해서 실제로 주행 중임이 입증되는 것은 아니므로 '수동 주행 중'이라고 추측하지 않습니다.

NUC에는 PID 저장소에서 새 라이다 테스트 스크립트 **2개**(`pid_lidar_test.sh`, `pid_lidar_test_monitor.py`)와 충돌 방지 기능을 반영한 `pid_ready.sh`를 함께 배포해야 합니다. 앱/브릿지 코드의 GitHub 반영은 NUC 배포나 실제 스마트폰 설치를 의미하지 않습니다. **Android Studio 빌드, NUC 단독 시험, ROS Master 재시작 뒤 브릿지 재구독, 실제 Bluetooth 테스트는 별도 검증이 필요합니다.**
