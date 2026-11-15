# 같은 증상, 다시 확인 · ReCheck

정비 전에 겪은 운전석 창문 움직임을 **정비 전 → 인수할 때 → 일상 재확인**의 같은 조건 기록으로 이어 보는 Pleos Connect 앱 시제품입니다. 차량이 제공한 창 위치를 읽고, 차주가 확인한 닫힘 기준에 대한 움직임을 비교합니다. 창문 제어, 고장 진단, 수리 완료 판정은 하지 않습니다.

버전 `0.1.0` · 패키지 `io.recheck.app` · 2026-11-15 검증본

## 바로 실행하기

제출용 서명 APK는 `apk/SSH_같은증상다시확인_release.apk`입니다. Android SDK Platform Tools의 `adb`가 있는 PC에서 대상 기기를 지정해 설치합니다.

```powershell
adb devices
adb -s <기기번호> install -r "apk/SSH_같은증상다시확인_release.apk"
adb -s <기기번호> shell am start -n io.recheck.app/.MainActivity
```

처음 실행하면 **DEMO · 모의 데이터** 모드입니다. 일반 Android 에뮬레이터에서도 기록·비교 흐름을 검토할 수 있습니다. 실제 차량값을 흉내 내어 표시하지 않으며, DEMO 기록과 Pleos 기록은 별도 파일에 저장합니다.

기존 설치본의 서명이 다르면 업데이트 설치가 거부될 수 있습니다. 검토용 별도 에뮬레이터를 사용해 주세요. 앱 삭제는 그 앱의 저장 기록도 삭제합니다.

## 시연 순서

1. 홈의 모의 데이터 표시를 확인하고 정비 전 관찰을 시작합니다. 확인 대화상자 뒤 약 5초 동안 가상의 창 움직임을 재생합니다.
2. 기록을 저장한 뒤 인수할 때의 관찰을 추가합니다. 가상 시나리오는 첫 기록의 되돌아옴과 인수 시 닫힘 관측을 대비합니다.
3. 일상 재확인을 추가하면 같은 형태의 되돌아옴이 다시 나타나는 가상 사례를 세 기록으로 볼 수 있습니다.
4. 기록 요약에서 조건·출처·수신값·한계를 확인하고 Android 공유 선택창을 열 수 있습니다. 수신 앱을 고르기 전에는 외부로 보내지 않습니다.
5. 앱을 종료하고 다시 열어도 저장한 DEMO 기록이 남습니다.

모의 기록을 초기화하려면 전용 검토 기기에서만 다음 명령을 실행합니다. 이 명령은 **DEMO 기록을 지웁니다**.

```powershell
adb -s <기기번호> shell am start -n io.recheck.app/.MainActivity --ez reset_demo true
```

## Pleos 연결 모드

홈에서 차량 연결 모드로 전환합니다. 이 경로는 공개 **Pleos Vehicle SDK 2.3.2**의 `Window` API를 사용합니다.

- `ROW_1_LEFT` 운전석 창 위치의 읽기 capability와 최소·최대 범위를 확인합니다.
- 현재 위치 조회와 위치 변경 구독만 사용합니다. 창문을 움직이는 API는 호출하지 않습니다.
- Android Car Service로 P 기어, 정지 속도, 운전자 주의 제한 해제를 확인해야 관찰을 시작할 수 있습니다. 속도 수신 시점이 5초를 넘거나 권한·서비스가 없으면 허용하지 않습니다.
- 차주가 창문을 완전히 닫은 상태를 눈으로 확인한 뒤 현재값을 닫힘 기준으로 등록합니다. 범위의 어느 끝이 닫힘인지는 임의로 정하지 않습니다.
- 조작 조건을 선택하고 창문 주변을 확인한 뒤, 평소의 물리 스위치를 닫기 방향으로 한 번 조작합니다. 앱은 수신한 위치만 기록합니다. 조작자는 측정하지 않습니다.
- 종료 시 현재값을 다시 요청합니다. 연결·주차 조건 상실, 화면 이탈, 값 오류, 부족한 관찰점 등은 관찰 불충분으로 처리합니다.

차량 읽기 권한은 `pleos.car.permission.CAR_WINDOWS`, `android.car.permission.CAR_POWERTRAIN`, `android.car.permission.CAR_SPEED`입니다. SDK가 연결용 `ai.pleos.playground.service.vehicle.VEHICLE_BINDING` 권한도 최종 Manifest에 병합합니다. 설치 대상의 권한 정책과 해당 차종의 capability가 충족되어야 합니다. 미지원 경로에서는 기록 시작을 막고 안내를 표시합니다.

## 실제 확인한 범위

| 구분 | 확인 결과 |
|---|---|
| 빌드 | 공식 Maven 저장소의 Vehicle 2.3.2를 연결해 실제 Java 컴파일 및 debug/release APK 생성 성공 |
| 단위 테스트 | `ObservationTest` 3건 통과. 되돌아옴/닫힘/불충분, 출처·시작 조건 구분, 범위·시간순서 오류 확인 |
| Android 저장 테스트 | `SessionPersistenceTest` 1건 통과. 3단계 재읽기, DEMO/PLEOS 파일 분리, 잘못된 출처 저장 거부 확인 |
| 일반 Android 시연 | Android 15 / API 35, 1600×900 에뮬레이터에서 실제 제출 APK를 실행해 3회 관찰·저장·비교·공유 선택창·강제종료 후 재열기 확인 |
| 중단 화면 | 명시적인 DEMO 불완전 관찰 fixture로 화면 확인. 실제 차량의 통신 장애를 재현한 시험은 아님 |
| 일반 Android 차량 연결 | 차량 서비스가 없는 환경의 연결 안내 확인. Pleos 신호 수신 성공을 뜻하지 않음 |
| Pleos 전용 이미지 | 공식 Pleos 2.1.0 / Android API 34 이미지 cold boot, 동일 제출 APK 설치·실행, 권한 허용, Vehicle 2.3.2 창 위치 읽기 연결 및 P·정지 확인 성공 |
| Pleos SDK 수신·비교 | VirtualConsole에서 바꾼 에뮬레이터 창 위치를 실제 SDK 콜백으로 수신. 정비 전 7개·인수 시 5개·일상 7개의 값을 각각 관찰·저장·비교 성공. 실제 차량의 신호는 아님 |
| Pleos 주행 제한 | 관찰 도중 D 선택 시 플랫폼 사용 차단 화면 확인. P 복귀 시 진행 중 관찰이 관찰 불충분(1개 값)으로 남는 것을 확인하고 저장 없이 취소 |
| Pleos 기록 복원 | 강제종료 후 일반 실행 시 기본 DEMO 모드 표시. Pleos 차량 읽기를 다시 선택하자 저장한 3개 기록과 7/5/7개 수신점의 비교 화면 복원 확인 |
| Gradle Wrapper | 포함된 `gradlew.bat --version`으로 Gradle 8.10.2 실행 확인. 개인 설정·키를 제외한 별도 소스 사본에서 debug/release/단위 테스트 작업 구성(`--dry-run`)도 확인. 사본의 전체 재빌드는 아님 |
| APK 서명 | Android `apksigner verify` 통과, APK Signature Scheme v2, 중립적인 시제품 인증서 사용 |

일반 Android API 35 / 1600×900 환경과 Pleos 2.1.0 / API 34 / 전체 화면 2560×1440 환경은 별개입니다. 빌드 및 3+1건 테스트는 자동 실행 결과이며, 라이브 SDK 수신·D/P 전환·강제종료 복원은 실제 에뮬레이터의 수동 검수입니다. Pleos 시험 절차와 근거 화면은 `evidence/pleos/README.md`에 정리했습니다. SDK 연결은 실행으로 확인했으나 실제 차종 지원이나 실차 작동을 확인했다는 뜻은 아닙니다.

`evidence/`에는 실행 화면, 원본 앱 녹화, 장면 시점, 개인정보를 제외한 검증 요약을 보관했습니다. `recheck-session-actual-apk.mp4`는 실제 APK 화면의 DEMO 실행 녹화이며 실제 차량의 센서 기록 영상이 아닙니다. 화면의 시각은 검증 기기의 설정에 따릅니다.

## 소스에서 빌드하기

Windows 11, Eclipse Temurin JDK 21.0.7, Android SDK Platform 35, Build Tools 35.0.0에서 빌드했습니다. Java 소스 호환 수준은 17입니다. Android Gradle Plugin은 8.7.3, Gradle은 8.10.2로 고정했습니다. 빌드 최소 환경은 JDK 17 이상과 Android SDK Platform 35입니다. 앱은 최소 API 28, target API 34입니다.

1. Android Studio 또는 Android SDK Command-line Tools로 SDK Platform 35, Build Tools 35.0.0, Platform Tools를 설치합니다. 차량 API 컴파일에 Platform 35의 `optional/android.car.jar`가 필요합니다.
2. `JAVA_HOME`을 설치한 JDK, `ANDROID_HOME`을 본인 Android SDK 경로로 설정합니다. Android Studio가 만든 `local.properties`의 `sdk.dir`을 사용해도 됩니다. 이 파일은 환경마다 다르므로 배포본에 넣지 않았습니다.
3. 이 README가 있는 프로젝트 폴더에서 실행합니다. 최초 실행은 Gradle과 의존성을 내려받을 네트워크가 필요합니다.

```powershell
.\gradlew.bat --version
.\gradlew.bat :automotive:assembleDebug :automotive:testReleaseUnitTest :automotive:assembleRelease
```

macOS/Linux에서는 `sh ./gradlew`로 같은 작업을 실행할 수 있습니다. 해당 OS의 실제 빌드는 아직 검증하지 않았습니다.

- 개발 설치용: `automotive/build/outputs/apk/debug/automotive-debug.apk` — Android 기본 개발 서명
- 새 release 빌드: `automotive/build/outputs/apk/release/automotive-release-unsigned.apk` — 개인 서명 설정이 없을 때
- 제출에 사용한 기존 서명본: `apk/SSH_같은증상다시확인_release.apk`

제출 APK의 개인키·키 비밀번호는 포함하지 않았습니다. 따라서 소스로 생성한 APK와 제출 APK의 바이트/서명이 같지는 않습니다. 새 배포용 서명본이 필요하면 본인 키로 서명해야 합니다. 프로젝트 루트의 `signing.properties`에서 `storeFile`, `storePassword`, `keyAlias`, `keyPassword`를 지정하면 release 빌드가 해당 키를 사용합니다. `storeFile`의 상대 경로 기준은 `automotive/`입니다. 이 파일과 키는 공유하지 않습니다.

SDK는 다음 공식 저장소에서 의존성으로 받습니다. Vehicle AAR, JDK, Android SDK, Gradle 배포 압축파일, Pleos 시스템 이미지는 배포본에 복제하지 않았습니다.

```text
https://nexus-playground.pleos.ai/repository/maven-releases/
ai.pleos.playground:Vehicle:2.3.2
```

Android 기기에서 저장 테스트를 재실행하려면 다른 테스트와 충돌하지 않는 에뮬레이터 하나를 연결한 뒤 실행합니다. 테스트는 기존 테스트 대상 저장내용을 보관했다가 복원합니다.

```powershell
.\gradlew.bat :automotive:connectedDebugAndroidTest
```

선택적으로 Python 3의 `tools/capture_demo.py --adb <adb 실행파일> --serial <기기번호>`로 1600×900 전용 검토 에뮬레이터의 DEMO 흐름을 다시 녹화할 수 있습니다. 고정 화면 좌표에 의존하며 해당 기기의 DEMO 기록을 초기화합니다. 다른 기기나 실차 대상으로 실행하지 마세요.

## 기록과 판정의 한계

수신값·앱 수신 경과시간·관찰 시각·출처·조건·닫힘 기준을 기기 내부에 저장합니다. 위치, VIN, 차량번호, 이름, 음성은 수집하지 않으며 인터넷 권한이 없습니다. 공유는 사용자가 기록을 검토하고 선택한 앱으로만 진행됩니다.

결과 표시는 간단한 위치 궤적 규칙입니다. 3개 이상의 순서가 올바른 유효 관찰점과 차주 확인이 필요합니다. 전체 범위의 약 2%(최소 1)를 오차 허용치로 삼아 닫힘 기준에 접근한 뒤 멀어지는 패턴 등을 표시합니다. 같은 출처·사용자 선택 조건·수신 범위·닫힘 기준이며 시작 위치 차이가 범위의 10% 이내일 때만 비교합니다. 이 임계값의 차종별 타당성은 실차에서 검증하지 않았습니다.

시간축은 차량 센서의 측정 시각이 아닌 **앱이 콜백을 받은 시점**입니다. API가 제공하지 않는 센서 원시 시각, 스위치 입력, 움직임 원인, 날씨와 주변 환경은 측정하지 않습니다. 같은 조건 선택은 사용자 진술이며 동일 환경의 증명이 아닙니다. 창 끼임, 고장, 수리 성공, 차량 안전성을 판정하지 않습니다.

실차 연결·차종 지원·절감 효과·사용 빈도·정비소 수용성은 미검증입니다. 현재 구현은 운전석 창문 한 증상 유형과 하나의 이어지는 기록 사례를 다룹니다. 제품 입점/실차 배포에 필요한 별도 심사와 검증을 완료했다는 의미가 아닙니다.

## 파일 구성

```text
README.md
apk/SSH_같은증상다시확인_release.apk
automotive/src/main/          앱 소스와 Manifest
automotive/src/test/          관찰 규칙 단위 테스트
automotive/src/androidTest/   Android 저장·출처 분리 테스트
gradle/wrapper/               Gradle Wrapper JAR와 버전 설정
gradlew / gradlew.bat         실행 스크립트
evidence/                    실제 앱 시연 및 검증 기록
tools/capture_demo.py         전용 에뮬레이터용 선택 녹화 도구
```

APK SHA-256: `b4baaed30b5ea72b32fa236e5c859d9088478aaf05477347ee078da1f2ee6e46`

서명 인증서 SHA-256: `84f559faf04ab7a23113d9a65043480848e9cf0042704ef9c2415bcfc3b51400`
