> **출처 및 저작권 (Source & Attribution)**
>
> - **직접 기반으로 사용한 안드로이드 앱:** [gyeong-yeon2718/uniconlab-wheelchair-operator-app](https://github.com/gyeong-yeon2718/uniconlab-wheelchair-operator-app)
> - **최초 원본 프로젝트:** [Geppetto0608/edge-mobility-monitor](https://github.com/Geppetto0608/edge-mobility-monitor)
> - **원저작자:** 박형준 (Park Hyeongjun), © 2026. All rights reserved.
> - 본 프로젝트는 원저작자의 허락을 받아 제공된 코드를 기반으로 개발합니다. 자세한 출처, 저작권 및 이용 조건은 [NOTICE.md](NOTICE.md)와 [LICENSE](LICENSE)를 참고하세요. 이 저장소의 공개 여부가 제3자에게 별도의 사용·재배포 권한을 부여하는 것은 아닙니다.

# UNICON Wheelchair App

UNICON Lab 전동휠체어의 **ROS1 FF–PD–Yaw 자율주행 시스템**을 안드로이드 스마트폰에서 운용하기 위한 앱입니다.

기존 Bluetooth 기반 운영 앱을 재사용하되, 실험자가 휠체어의 정합·기동 상태를 확인하고 탑승자가 경로를 선택하여 주행할 수 있도록 **두 화면으로 개편하는 프로젝트**입니다.

> **개발 상태:** 현재 저장소의 Android 코드는 기존 운영 앱을 기반으로 합니다. 아래의 2화면 UI, 새 지도 및 6개 경로, FF–PD/Safety 연동은 **개편 목표**이며, README 수정만으로 구현된 기능은 아닙니다.

## 앱 화면 구성 (개편 목표)

하나의 앱에서 **실험자용 / 탑승자용** 두 화면을 자유롭게 전환합니다. 로그인 또는 역할별 접근 권한 분리는 하지 않습니다. 다른 팀원도 터미널 명령 없이 앱에서 정합과 주행을 요청할 수 있도록 구성합니다.

### 1. 실험자용

- **정합·시스템 기동 버튼:** 센서, FAST-LIO, GICP 등의 READY 절차 요청
- **작업 진행 상태:** 현재 실행 단계, 완료·진행·실패 표시
- **시스템 진단:** LiDAR/IMU, 위치추정, 인지, 모터/UART, 제어기, 통신 상태
- **오류 상세:** 실패 단계, 오류 코드, 주행 불가 이유 확인
- **Safety 상태:** CLEAR / SLOW / STOP 및 판단 사유
- **E-STOP:** 소프트웨어 정지 요청

### 2. 탑승자용

- **READY:** ROS가 전달한 주행 가능 상태 표시
- **경로 선택:** A↔D4, A↔D5, D4↔D5 방향별 총 6개 경로
- **주행 조작:** 선택 경로로 출발, 주행 정지, 명시적 재출발
- **2D 지도:** 새 지도 배경 위에 선택 경로, 휠체어의 실시간 위치·방향 표시
- **현재 속도:** 속도 표시
- **Safety 상태 및 사유:** CLEAR(초록), SLOW(노랑·주황), STOP(빨강)
- **E-STOP:** 소프트웨어 정지 요청

경로 진행 상황은 **지도 위의 현재 위치**로 표현합니다. 진행률 퍼센트, 남은 거리, 도착 예상 시간, 별도 장애물 위치 지도는 1차 화면에서 제외합니다.

### Safety 상태 표시 원칙

| 표시 상태 | 색상 | 사유 표시 예시 |
| --- | --- | --- |
| `CLEAR` | 초록 | 정상 주행 |
| `SLOW` | 노랑/주황 | 장애물 감지, 커브 구간 감속, 경로 이탈 위험 |
| `STOP` | 빨강 | 장애물 감지, 경로 이탈 감지, 위치추정 오류, 목적지 도착 |

- 앱은 **Safety/제어 노드가 발행한 결과를 표시**할 뿐, 위험 여부나 재출발 가능 여부를 자체 판단하지 않습니다.
- `CTE_LIMIT` → **경로 이탈 위험**, `MASK_BOUNDARY` → **경로 이탈 감지** 등 원본 reason 코드를 사용자용 문구로 변환합니다. 내부 판단 기준은 변경하지 않습니다.
- 장애물 정지 후 자동 재출발, 위치추정 이상·마스크 경계 정지 후 명시적 재출발 등은 **PID Node의 정책**에 따릅니다.
- Safety의 상세 오류와 시스템 기동 오류는 실험자용 화면에서 더 자세히 표시합니다.

## 시스템 구성

```text
Android App (Java / XML)
  ├─ 실험자용: 정합 요청 · READY 진행 상태 · 진단/오류
  └─ 탑승자용: 경로 선택 · 주행 · 지도/위치 · Safety
          ⇅ Bluetooth SPP (RFCOMM, 줄 단위 JSON)
NUC: scripts/ros1_bluetooth_bridge.py
          ⇅ ROS1 Noetic 토픽 / 서비스 / 실행 스크립트
  ├─ pid_ready.sh / pid_ready_monitor.py   : 센서·정합 READY
  ├─ pid_drive.sh / pid_stop.sh            : 경로별 주행 시작·정지
  ├─ ff_pid_node.py / ff_pid_core.py       : FF–PD–Yaw 제어
  ├─ pid_safety.py                         : CLEAR/SLOW/STOP 판단
  ├─ /fast_lio_icp/pose                    : 현재 위치
  └─ /wheel_status                        : 구동부 상태
```

앱은 ROS에 직접 연결하지 않습니다. **Bluetooth Bridge가 앱 명령을 ROS에 전달하고 ROS 데이터를 앱에 전달**합니다. 기존 브릿지는 구형 `waypoint_follower`를 기준으로 작성되어 있으므로 FF–PD용 명령·상태 연동이 필요합니다.

### ROS 연동 기준 (개편 목표)

| 대상 | 실제 인터페이스/스크립트 | 앱에서의 역할 |
| --- | --- | --- |
| 시스템 준비 | `pid_ready.sh`, `pid_ready_monitor.py` | 정합 시작, 준비 단계·실패 이유 |
| 선택 경로 주행 | `pid_drive.sh <route>` | 선택한 경로로 주행 요청 |
| 주행 중단 | `pid_stop.sh`, `/ff_pid/start` | 주행 정지·재시작 요청 |
| 제어기 상태 | `/ff_pid/status` | 주행 상태·제어 오류 표시 |
| Safety 상태 | `/pid_safety/status` | 상태·이유·속도 제한 표시 |
| 위치 | `/fast_lio_icp/pose` | 지도 위 현재 위치·방향 |

Safety 개발 기준: [개인 PID 저장소의 safety-rebuild 브랜치](https://github.com/Iamshy95/UNICON_wheelchair_PID/tree/feature/safety-rebuild-20261108). 이 브랜치의 Safety 정책에는 `state`, `reason`, `speed_limit_mps`, `route`, `stamp`가 포함됩니다. 앱은 이를 브릿지를 통해 수신할 예정입니다.

## 지도 및 경로

기존 `RouteMapView.java`의 지도·경로 표시 기능을 재사용하되 **이전 지도 배경 및 경로 데이터는 새 데이터로 교체**합니다. 지도 배경의 원점·해상도와 ROS `map` 좌표계를 일치시켜야 합니다.

사용할 경로 이름:

| 경로 키 | 방향 |
| --- | --- |
| `A_to_D4` | A → D4 |
| `A_to_D5` | A → D5 |
| `D4_to_A` | D4 → A |
| `D4_to_D5` | D4 → D5 |
| `D5_to_A` | D5 → A |
| `D5_to_D4` | D5 → D4 |

팀 프로젝트의 `routes/route2_manual_20261031/` 및 별도 `route_editor/generated_routes/`의 최신 데이터를 확인하여 적용합니다. 경로는 앱과 제어기가 **같은 파일·같은 좌표계**를 기준으로 사용하도록 연동합니다.

## 개발 작업공간

현재 Ubuntu 작업공간(`~/ws/wheelchair_ws`)의 주요 폴더:

```text
wheelchair_ws/
├── UNICON_wheelchair_app/    # 본 안드로이드 앱 저장소
├── UNICON_wheelchair_v2/     # 팀 ROS1 통합 시스템 및 Bluetooth Bridge
├── route_editor/             # 지도·경로 편집 및 6개 경로 데이터
└── android_app/              # 별도 작업 폴더
```

관련 GitHub 저장소:

- [개인 FF–PD 제어 및 Safety 개발](https://github.com/Iamshy95/UNICON_wheelchair_PID)
- [팀 UNICON 휠체어 통합 시스템](https://github.com/Dannythechampion/UNICON_wheelchair_v2)
- [직접 기반이 된 기존 안드로이드 앱](https://github.com/gyeong-yeon2718/uniconlab-wheelchair-operator-app)

## 주요 Android 소스

| 경로 | 역할 |
| --- | --- |
| `app/src/main/java/com/example/jetsonbtmonitor/MainActivity.java` | 화면 동작, 버튼, 상태 표시 |
| `app/src/main/java/com/example/jetsonbtmonitor/BluetoothWheelchairClient.java` | Bluetooth 연결·송수신·재연결 |
| `app/src/main/java/com/example/jetsonbtmonitor/RouteMapView.java` | 2D 지도, 경로, 현재 위치 표시 |
| `app/src/main/res/layout/activity_main.xml` | 현재 대시보드 레이아웃(2화면 개편 대상) |
| `app/src/main/java/com/example/jetsonbtmonitor/AppConfig.java` | 앱 설정 |

## 빌드 (Ubuntu / Android Studio)

Android Studio와 Android SDK, 프로젝트의 Gradle 설정에 맞는 JDK가 필요합니다. 기존 프로젝트 기준 Gradle 데몬 툴체인은 JDK 21입니다.

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

USB 디버깅이 활성화된 Android 기기에서:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 개발 진행 상태

- [x] 기존 앱 소스 및 Bluetooth 통신 구조 확보
- [x] 개인 GitHub 앱 저장소 생성
- [ ] 실험자용·탑승자용 화면 개편
- [ ] 새 지도 및 6개 경로 적용
- [ ] Safety/제어 상태 및 오류 문구 연결
- [ ] 정합·경로 선택·주행 명령을 FF–PD 브릿지에 연결
- [ ] 앱 빌드, 통신 및 실제 시스템 연동 확인

## 저작권 및 이용 조건

이 프로젝트는 상단에 명시한 기존 앱과 최초 원본을 기반으로 합니다. 원저작자 표시 및 기존 고지 파일([NOTICE.md](NOTICE.md), [LICENSE](LICENSE))을 유지합니다. 원본에 대한 권리는 각 저작권자에게 있으며, 이 저장소의 공개를 별도의 오픈소스 라이선스 부여로 해석해서는 안 됩니다.
