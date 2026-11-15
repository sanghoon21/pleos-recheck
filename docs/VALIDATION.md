# 실행 검증

| 항목 | 실행 환경 |
|---|---|
| APK | ReCheck 0.3.0, versionCode 3, `io.recheck.app` |
| 일반 Android | Android 15 / API 35, 1600×900 |
| Pleos | 공식 Pleos Connect 2.1.0 / API 34, 전체 화면 2560×1440 |
| SDK | `ai.pleos.playground:Vehicle:2.3.2` |
| SDK 시험 입력 | Virtual Console 가상 차량, 운전석 창문 `ROW_1_LEFT` |
| 시험 도구 | Android Emulator 37.2.13.0, Platform Tools 37.0.1 |

## 사용 흐름

세 시점 기록·비교·상담용 텍스트 공유 선택창과 앱 재실행 후 복원을 확인했습니다. 앱에서 직접 기록을 시작하고 저장했습니다. [기록 중](../verification/record.png) · [이번 결과](../verification/result.png) · [세 시점 비교](../verification/compare.png) · [움직임 자세히](../verification/detail.png) · [상담 기록](../verification/report.png)

시연의 위치값은 앱 내부의 모의값입니다. 앞의 사용 화면과 아래 SDK 검증은 다른 데이터 경로를 사용합니다.

## SDK로 확인한 동작

운전석 창문 현재 위치 조회, 가상 차량 신호로 세 관찰의 신규 저장·비교와 앱 재실행 후 복원을 확인했습니다.

1. 앱을 열고 차량 데이터 읽기를 선택한 뒤 P·정지와 읽기 연결을 확인합니다. 닫힌 위치를 조회해 기준 0을 등록했습니다. [현재 위치 조회](../verification/pleos/sdk-current-value.png)
2. 같은 조건을 선택하고 단계별로 기록을 시작합니다. Virtual Console 가상 차량의 운전석 창문에 다음 위치값을 입력하고 기록을 마칩니다.

| 단계 | 위치 입력 순서 | 저장한 수신점 | 앱 결과 |
|---|---|---:|---|
| 정비 전 | 78 → 60 → 29 → 8 → 25 → 41 | 7 | 다시 내려옴 |
| 인수할 때 | 78 → 56 → 26 → 0 | 5 | 닫힘 확인 |
| 일상 재확인 | 77 → 58 → 27 → 9 → 23 → 39 | 7 | 다시 내려옴 |

3. 비교 화면을 확인하고 앱을 강제 종료합니다. 다시 실행한 뒤 차량 데이터 읽기를 선택해 동일한 세 기록이 열리는지 확인했습니다. [새 비교](../verification/pleos/compare-fresh.png) · [재실행 뒤](../verification/pleos/compare-reopened.png)

기록 중 D 전환에서 플랫폼의 사용 제한이 표시됐고, P로 돌아왔을 때 남은 시도가 관찰 불충분으로 표시됐습니다.

[D 사용 제한](../verification/pleos/driving-platform-restriction.png) · [P 복귀 뒤 불충분](../verification/pleos/returned-park-interrupted.png)

SDK가 콜백으로 보낸 값을 기록 도중 수신합니다. 시작과 종료 때도 현재 위치를 조회하므로, 화면에 표시된 수신점 수에는 끝점 조회가 포함됩니다. 실제 차종에서의 신호 지원과 연동 품질은 이후 검증할 항목입니다.

[SDK 연동 검증 영상](https://github.com/sanghoon21/pleos-recheck/releases/download/v0.3.0/same-symptom_pleos-sdk-verification_v0.3.0.mp4) · [시험 환경과 실측값 JSON](../verification/pleos/verification.json)

## 자동 검사

v0.3.0 서명 APK를 빌드하고 단위 검사 3건을 확인했습니다. Android 저장·출처 분리 검사 1건도 통과했습니다.

`ObservationTest`는 기록 결과 분류, 비교 조건, 유효값·시각 순서를 검사합니다. `SessionPersistenceTest`는 로컬 저장의 재읽기와 모의 데이터·SDK 수신 데이터의 분리를 검사합니다. 재현 명령은 [개발 환경과 빌드](DEVELOPMENT.md)에 있습니다.

## 제출 APK

```text
SHA-256
c80c15e6b29ba93f600c029f83d33b92aab3a725c4c4592c54a1d17f69b861ed
```
