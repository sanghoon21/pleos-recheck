# 개발 환경과 빌드

ReCheck는 Java로 작성한 Android 앱입니다. v0.4.0은 받은 위치에 따라 창문 그림을 갱신하고, 기록을 시작할 때와 결과를 저장할 때 필요한 조작을 화면에서 안내합니다. 비교에서는 세 결과부터 보여줍니다. 그래프와 조건은 사용자가 해당 기록을 열면 표시됩니다. 실행 결과는 [검증 기록](VALIDATION.md)에 있습니다.

## 빌드 환경

| 항목 | 설정 |
|---|---|
| 검증한 PC | Windows 11, Eclipse Temurin JDK 21.0.7 |
| Java 소스 호환 수준 | 17 |
| Gradle / Android Gradle Plugin | 8.10.2 / 8.7.3 |
| Android SDK | compile 35, target 34, min 28 |
| Build Tools | 35.0.0 |
| 차량 SDK | `ai.pleos.playground:Vehicle:2.3.2` |

JDK 17 이상과 Android SDK Platform 35, Build Tools 35.0.0, Platform Tools가 필요합니다. 차량 API 컴파일에는 Platform 35의 `optional/android.car.jar`를 사용합니다.

## 소스에서 빌드하기

`JAVA_HOME`을 JDK 경로로, `ANDROID_HOME`을 Android SDK 경로로 설정합니다. Android Studio가 만든 `local.properties`의 `sdk.dir`을 사용해도 됩니다. 최초 빌드는 Gradle과 의존성을 내려받을 네트워크가 필요합니다.

프로젝트 루트에서 실행합니다.

```powershell
.\gradlew.bat --version
.\gradlew.bat :automotive:assembleDebug :automotive:testReleaseUnitTest :automotive:assembleRelease
```

| 결과물 | 경로 |
|---|---|
| 개발 서명 APK | `automotive/build/outputs/apk/debug/automotive-debug.apk` |
| 별도 서명 설정이 없는 release APK | `automotive/build/outputs/apk/release/automotive-release-unsigned.apk` |

macOS/Linux에서는 `sh ./gradlew`로 같은 작업을 실행할 수 있습니다. 실제 빌드 검수는 Windows에서 수행했습니다.

Android 저장 검사는 검토용 에뮬레이터 한 대를 연결한 뒤 실행합니다.

```powershell
.\gradlew.bat :automotive:connectedDebugAndroidTest
```

## 배포용 서명

공개 소스에는 제출 APK의 개인키와 비밀번호가 없습니다. 새 배포용 APK는 본인 키로 서명해야 합니다. 프로젝트 루트의 비공개 `signing.properties`에 다음 속성을 지정하면 release 빌드가 해당 키를 사용합니다.

```properties
storeFile=<키 파일 경로>
storePassword=<키 저장소 비밀번호>
keyAlias=<키 별칭>
keyPassword=<키 비밀번호>
```

`storeFile`의 상대 경로 기준은 `automotive/`입니다. `signing.properties`, `local.properties`, 키 파일과 빌드 산출물은 `.gitignore`에 포함되어 있습니다. 직접 빌드한 APK는 공개 릴리스 APK와 서명·해시가 다를 수 있습니다.

Vehicle SDK는 `settings.gradle`에 지정한 공식 Maven 저장소에서 받습니다.

```text
https://nexus-playground.pleos.ai/repository/maven-releases/
ai.pleos.playground:Vehicle:2.3.2
```

Vehicle AAR, JDK, Android SDK, Gradle 배포 압축파일과 Pleos 시스템 이미지는 소스 묶음에 복제하지 않았습니다.

## 차량 읽기와 주차 조건

`VehicleReader`는 운전석 창문 `ROW_1_LEFT`의 읽기 지원과 유효 범위를 확인한 뒤 현재 위치를 조회하고 변경값을 구독합니다. 사용하는 주요 API는 `checkWindowPositionCapabilities`, `getCurrentWindowPosition`, `registerWindowPosition`입니다. 관찰 종료 시 현재값을 다시 조회하고, 연결을 닫을 때 구독을 해제합니다.

닫힘 기준은 차주가 창문이 완전히 닫혔음을 확인한 뒤 현재값으로 등록합니다. SDK 범위의 최솟값이나 최댓값을 임의로 닫힘으로 정하지 않습니다.

`CarSafetyGate`는 Android Car Service로 다음 조건을 확인합니다.

- 기어가 P이고 속도가 0에 가까울 것
- 속도값이 최근 5초 안의 값일 것
- 플랫폼에서 운전자 주의 제한을 요구하지 않을 것

조건을 확인할 수 없으면 관찰을 허용하지 않습니다. 관찰 중 연결·주차 조건 상실이나 화면 이탈 등이 발생하면 결과를 불충분으로 처리합니다. 창문 제어 API는 호출하지 않습니다.

| 권한 | 용도 |
|---|---|
| `pleos.car.permission.CAR_WINDOWS` | 창 위치 읽기 |
| `android.car.permission.CAR_POWERTRAIN` | P 기어 확인 |
| `android.car.permission.CAR_SPEED` | 정지 상태 확인 |
| `ai.pleos.playground.service.vehicle.VEHICLE_BINDING` | SDK가 병합하는 차량 서비스 연결 권한 |

대상 환경의 권한 정책과 차량 capability가 충족되어야 합니다. Manifest의 권한 선언만으로 실차 지원이 보장되지는 않습니다.

## 관찰과 비교 기준

`Observation`은 수신 위치값, 앱 수신 경과시간, 관찰 시각, 출처, 조작 조건, 수신 범위와 닫힘 기준을 갖습니다. 결과를 표시하려면 차주의 확인과 시간순서가 올바른 유효 관찰점 3개 이상이 필요합니다.

- 닫힘 기준에 가까워졌다 다시 멀어진 흐름은 **올라간 뒤 되돌아옴**으로 표시합니다.
- 닫히는 방향으로 움직인 뒤 기준 부근에서 끝나면 **닫힘 위치 관측**으로 표시합니다.
- 중단되었거나 값이 부족하면 **관찰 불충분**으로 표시합니다.

위치 오차 허용치는 전체 범위의 약 2%(최소 1)입니다. 비교는 같은 출처·조작 조건·수신 범위·닫힘 기준이고, 시작 위치 차이가 범위의 10% 이내인 유효 기록에 한합니다. 이 기준의 차종별 타당성은 실차 검증 대상입니다.

시간축은 앱이 콜백을 받은 시점을 기준으로 합니다. 조작 조건은 사용자가 입력합니다. 기록에 저장되는 것은 창 위치와 앱 수신 시각입니다.

## 저장과 공유

`CaseStore`는 Android 앱 전용 공간에 `AtomicFile`로 JSON을 저장합니다. 모의 관찰은 `demo-case.json`, SDK 수신 관찰은 `vehicle-case.json`으로 분리하며, 파일과 기록의 출처가 다르면 저장·읽기를 거부합니다.

공유 전 화면에는 단계별 결과가 표시됩니다. 공유하는 텍스트에는 각 관찰의 조건과 위치값이 들어갑니다. `ReportProvider`는 해당 보고서를 읽기 전용 URI로 제공하고, Android 공유 선택창에서 사용자가 고른 앱에 읽기 권한을 전달합니다. 앱 자체에는 인터넷 권한이 없습니다.

## 소스 구성

| 파일 | 역할 |
|---|---|
| `MainActivity.java` | 화면, 관찰 진행, 비교·공유 흐름 |
| `WindowIllustration.java` | 수신 위치에 따라 움직이는 창문 그림 |
| `MovementChart.java` | 상세 화면에서 한 기록의 위치값 표시 |
| `VehicleReader.java` | Pleos 창 위치 capability·조회·구독 |
| `CarSafetyGate.java` | P·속도·운전자 주의 제한 확인 |
| `Observation.java` | 유효성, 움직임 결과, 비교 가능 조건 |
| `CaseStore.java` | 출처를 분리한 원자적 저장·복원 |
| `ReportProvider.java` | 사용자가 검토한 텍스트 보고서 공유 |
| `ObservationTest.java` | 관찰 규칙 단위 검사 3건 |
| `SessionPersistenceTest.java` | Android 저장·출처 분리 검사 1건 |

앱 소스는 `automotive/src/main/`, 단위 검사는 `automotive/src/test/`, Android 검사는 `automotive/src/androidTest/`에 있습니다.
