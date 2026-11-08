> **출처 및 저작권 (Source & Attribution)**
>
> - **기반이 된 원본 Android 앱:** https://github.com/gyeong-yeon2718/uniconlab-wheelchair-operator-app
> - **최초 원본 프로젝트:** https://github.com/Geppetto0608/edge-mobility-monitor
> - **원저작자:** 박형준 (Park Hyeongjun), © 2026. All rights reserved.
> - 원저작자의 허락을 받아 제공된 코드를 바탕으로 개발했습니다. 사용·재배포 조건은 [NOTICE.md](NOTICE.md), [LICENSE](LICENSE)를 참고하세요. 공개 저장소라고 해서 제3자에게 별도 이용 권한이 부여되는 것은 아닙니다.

# UNICON FF–PID 휠체어 Android 앱

한양대학교 UNICON Lab의 **ROS1 Noetic FF–PID–Safety 기반 전동휠체어**를 스마트폰에서 운용하는 전용 Android 앱입니다. 원본 팀 운영 앱은 별도 저장소에 남아 있으므로, 이 저장소는 FF–PID용 코드만 유지합니다.

## 확정된 화면

**관리자:** Bluetooth 연결 버튼 및 연결 상태, READY 시작·종료, READY 진행상태 및 LiDAR·IMU·FAST-LIO·GICP·인지·휠·UART 모니터링.

**탑승자:** Bluetooth 연결 상태, A·D4·D5 간 6개 경로 선택, 주행 시작·종료, 출발 거부 시 위치·방향 조정 안내, 실제 주행 중 CLEAR/SLOW/STOP와 이유, 드라이버블 마스크 지도·선택 경로·실시간 위치/방향.

Bluetooth 기기 선택 화면, 연결 해제 버튼, 앱 비상정지 버튼, 로그인, 카메라, 프리셋, 장애물 별도 뷰는 사용하지 않습니다. 실제 긴급 정지는 물리적 조이스틱 수단을 유지해야 합니다. 앱의 주행 종료는 `pid_stop.sh`입니다.

## 코드 구성

- `app/src/main/java/com/example/jetsonbtmonitor/PidOperatorActivity.java`: **관리자/탑승자 전용 화면** 및 FF–PID 메시지 연동.
- `app/src/main/java/com/example/jetsonbtmonitor/PidRouteMapView.java`: 새 ROS `map` 좌표계 지도·경로·위치 그리기.
- `app/src/main/java/com/example/jetsonbtmonitor/BluetoothWheelchairClient.java`: Android Bluetooth Classic RFCOMM 클라이언트·자동 재연결.
- `nuc/pid_bt_bridge.py`: 별도 UUID/RFCOMM 채널 7의 **FF–PID 전용 BlueZ/ROS1 브릿지**.
- `nuc/README.md`: 메시지 형식 및 NUC에서 수동 시험하는 방법.
- `app/src/main/AndroidManifest.xml`: 관리자/탑승자 화면을 시작 화면으로 지정.

## 실행 구조

```text
Android 앱 (Bluetooth UUID 9a1f17cc-18d2-45f0-b6e7-1b0e257a902e)
             │ Bluetooth SPP (RFCOMM)
NUC: nuc/pid_bt_bridge.py
             │ 작업 실행 / ROS 상태 구독
NUC: pid_ready.sh · pid_drive.sh · pid_stop.sh
             │
        기존 FF–PID / Safety ROS1 노드
```

기존 팀 `ros1_bluetooth_bridge.py`는 **건드리지 않으며** 별도 UUID·채널을 이용합니다. 브릿지는 스스로 `/cmd_vel`을 발행하지 않습니다. DRIVE 성공은 출발 서비스 승인 ACK가 아니라 실제 ROS `RUNNING` 상태로 확인합니다.

### Android Studio

Ubuntu PC의 기존 프로젝트에서 최신 코드를 내려받습니다.

```bash
cd ~/ws/wheelchair_ws/UNICON_wheelchair_app
git pull origin main
```

Android Studio에서 **Build > Make Project** 또는 Gradle `:app:assembleDebug`로 컴파일하고, 스마트폰에 설치해 화면과 Bluetooth 통신을 확인합니다. 블루투스 기기 목록을 앱 안에서 선택하지 않으므로, NUC는 **Android 설정에서 미리 페어링**해야 합니다. 이전 앱의 저장된 NUC Bluetooth 주소가 있으면 재사용하고, 없으면 페어링된 이름으로 단일 NUC 후보를 찾습니다.

### NUC

GitHub 파일은 자동으로 NUC에 설치되지 않습니다. `nuc/pid_bt_bridge.py`를 NUC에 배포하고 ROS Noetic·BlueZ 환경에서 실행해야 합니다. 자동 부팅은 추후 **별도 systemd 서비스 등록**이 필요합니다. 스크립트·루트 경로는 실제 NUC 환경에 맞춰 지정합니다.

## 구현·검증 상태

- 앱 화면 구성 및 지도 렌더링 코드: **작성됨**, Android Studio 빌드 확인 전.
- 개인 PID `main`의 1.0m/30° 출발 검사 JSON 및 RUNNING 판정 브릿지 처리: **작성됨**, 실제 기기 통합시험 전.
- 지도: NUC의 `common_drivable_mask.pgm`을 전송해 사용하도록 작성.
- 기존 운영 코드 정리: 불필요한 이전 Wi-Fi/Jetson Java, 레이아웃, 리소스, ROS2 예제는 제거했으며 Git 이력과 원본 저장소에 남습니다.
- NUC 배포·systemd 자동실행·실기 안전 검증: **미완료**.

**중요:** 코드가 GitHub에 반영됐다는 것과 실제 휠체어에서 안전하게 동작함이 확인됐다는 것은 다릅니다.
