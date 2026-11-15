package io.recheck.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Toast;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class MainActivity extends Activity {
    private static final String[] STAGES = { "정비 전", "인수할 때", "일상 재확인" };
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Observation> observations = new ArrayList<>();
    private Dashboard dashboard;
    private boolean demo = true;
    private String screen = "home";
    private String connection = "가상 시나리오 · 실제 차량과 연결되지 않음";
    private String condition = "주차 후 첫 조작";
    private boolean parked;
    private boolean vehicleReady;
    private int minimum;
    private int maximum = 100;
    private Integer position;
    private Integer closedValue;
    private VehicleReader reader;
    private CarSafetyGate safety;
    private Observation recording;
    private long recordingStarted;
    private boolean recordingComplete;
    private boolean finalReadPending;
    private boolean loadFailed;
    private int selectedStage;
    private String error = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        dashboard = new Dashboard();
        setContentView(dashboard);
        load();
        applyIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyIntent(intent);
    }

    private void applyIntent(Intent intent) {
        String requestedScreen = intent.getStringExtra("screen");
        if (intent.getBooleanExtra("reset_demo", false) || intent.getBooleanExtra("showcase", false)
                || "incomplete".equals(requestedScreen)) setMode(true);
        if (intent.getBooleanExtra("reset_demo", false)) {
            demo = true;
            observations.clear();
            save();
        }
        if (intent.getBooleanExtra("showcase", false)) {
            demo = true;
            observations.clear();
            int count = Math.max(0, Math.min(3, intent.getIntExtra("stage", 3)));
            for (int i = 0; i < count; i++) observations.add(example(i, false));
            save();
        }
        if (requestedScreen != null) screen = requestedScreen;
        selectedStage = Math.max(0, Math.min(2, intent.getIntExtra("stage", observations.size() + 1) - 1));
        if ("incomplete".equals(screen)) {
            screen = "record";
            recording = example(selectedStage, true);
            recordingComplete = true;
            error = "접속이 끊겨 이번 움직임을 끝까지 확인하지 못했습니다.";
        }
        if ("unsupported".equals(screen)) {
            setMode(false);
        }
        dashboard.invalidate();
    }

    private void load() {
        observations.clear();
        loadFailed = false;
        try { observations.addAll(new CaseStore(this, demo).load()); }
        catch (Exception exception) { loadFailed = true; error = "저장 기록을 읽지 못했습니다. 새 기록을 저장할 수 없습니다."; }
    }

    private boolean save() {
        if (loadFailed) return false;
        try {
            new CaseStore(this, demo).save(observations);
            return true;
        } catch (Exception exception) {
            error = "저장하지 못했습니다. 기록을 유지한 채 다시 시도해 주세요.";
            dashboard.invalidate();
            return false;
        }
    }

    private void setMode(boolean requestedDemo) {
        stopLive();
        handler.removeCallbacksAndMessages(null);
        if (recording != null && !recordingComplete) recording.interrupted = true;
        recording = null;
        finalReadPending = false;
        error = "";
        demo = requestedDemo;
        screen = "home";
        load();
        connection = demo ? "가상 시나리오 · 실제 차량과 연결되지 않음" : "Pleos Vehicle 서비스 확인 중";
        closedValue = null;
        position = null;
        if (!demo) connectLive();
        dashboard.invalidate();
    }

    private void connectLive() {
        if (!getPackageManager().hasSystemFeature("android.hardware.type.automotive")) {
            connection = "Pleos Connect 차량 또는 에뮬레이터에서 연결해 주세요.";
            return;
        }
        String[] permissions = { "android.car.permission.CAR_SPEED", "android.car.permission.CAR_POWERTRAIN" };
        List<String> missing = new ArrayList<>();
        for (String permission : permissions) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) missing.add(permission);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), 12);
            return;
        }
        try {
            safety = new CarSafetyGate(this, (allowed, reason) -> runOnUiThread(() -> {
                parked = allowed;
                if (!allowed && recording != null && !recordingComplete) {
                    recording.interrupted = true;
                    recordingComplete = true;
                    error = reason;
                }
                dashboard.invalidate();
            }));
            reader = new VehicleReader(this, new VehicleReader.Listener() {
                @Override public void onReady(int low, int high) {
                    minimum = low;
                    maximum = high;
                    vehicleReady = true;
                    connection = "Pleos Vehicle 2.3.2 · 운전석 창 위치 읽기 연결";
                    dashboard.invalidate();
                }
                @Override public void onPosition(int value) {
                    position = value;
                    if (recording != null && !recordingComplete && parked) {
                        long elapsed = SystemClock.elapsedRealtime() - recordingStarted;
                        // Receipt time belongs to the app, not an undocumented vehicle clock.
                        if (!recording.points.isEmpty() && elapsed == recording.points.get(recording.points.size() - 1).elapsedMs)
                            recording.points.set(recording.points.size() - 1, new Observation.Point(elapsed, value));
                        else recording.points.add(new Observation.Point(elapsed, value));
                    }
                    dashboard.invalidate();
                }
                @Override public void onError(String message) {
                    error = message;
                    vehicleReady = false;
                    if (recording != null && !recordingComplete) {
                        recording.interrupted = true;
                        recordingComplete = true;
                    }
                    dashboard.invalidate();
                }
            });
            reader.connect();
        } catch (Exception | LinkageError exception) {
            connection = "차량 서비스·권한을 확인할 수 없습니다.";
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 12) {
            for (int result : results) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    connection = "주차 상태 확인에 필요한 차량 읽기 권한이 없습니다.";
                    dashboard.invalidate();
                    return;
                }
            }
            connectLive();
        }
    }

    private void stopLive() {
        if (reader != null) reader.close();
        if (safety != null) safety.close();
        reader = null;
        safety = null;
        parked = false;
        vehicleReady = false;
    }

    @Override protected void onPause() {
        super.onPause();
        if (recording != null && !recordingComplete) {
            recording.interrupted = true;
            recordingComplete = true;
            error = "화면을 벗어나 관찰이 중단됐습니다.";
        }
        handler.removeCallbacksAndMessages(null);
        stopLive();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!demo && reader == null) connectLive();
        if (dashboard != null) dashboard.invalidate();
    }

    private Observation example(int stage, boolean interrupted) {
        Observation observation = new Observation();
        observation.id = UUID.randomUUID().toString();
        observation.source = "DEMO";
        observation.stage = STAGES[stage];
        observation.conditions = condition;
        observation.capturedAt = System.currentTimeMillis();
        observation.minimum = 0;
        observation.maximum = 100;
        observation.closedValue = 0;
        observation.userConfirmed = true;
        observation.interrupted = interrupted;
        int[] values = stage == 1 ? new int[] {80, 74, 64, 52, 38, 22, 9, 0, 0, 0}
                : new int[] {80, 74, 64, 52, 38, 22, 9, 20, 34, 40};
        int limit = interrupted ? 4 : values.length;
        for (int i = 0; i < limit; i++) observation.points.add(new Observation.Point(i * 500L, values[i]));
        return observation;
    }

    private void startObservation(int stage) {
        if (loadFailed) { dashboard.invalidate(); return; }
        if (!demo && safety != null) parked = safety.refreshNow();
        if (!demo && (!parked || !vehicleReady || closedValue == null)) {
            error = "연결·P 상태와 닫힌 위치 기준을 먼저 확인해 주세요.";
            dashboard.invalidate();
            return;
        }
        selectedStage = stage;
        recording = new Observation();
        recording.id = UUID.randomUUID().toString();
        recording.source = demo ? "DEMO" : "PLEOS";
        recording.stage = STAGES[stage];
        recording.conditions = condition;
        recording.capturedAt = System.currentTimeMillis();
        recording.minimum = demo ? 0 : minimum;
        recording.maximum = demo ? 100 : maximum;
        recording.closedValue = demo ? 0 : closedValue;
        recording.userConfirmed = true;
        recordingStarted = SystemClock.elapsedRealtime();
        recordingComplete = false;
        finalReadPending = false;
        screen = "record";
        error = "";
        if (demo) {
            Observation fixture = example(stage, false);
            for (int i = 0; i < fixture.points.size(); i++) {
                final int index = i;
                handler.postDelayed(() -> {
                    if (recording == null || recordingComplete) return;
                    recording.points.add(fixture.points.get(index));
                    position = fixture.points.get(index).rawPosition;
                    if (index == fixture.points.size() - 1) recordingComplete = true;
                    dashboard.invalidate();
                }, i * 500L);
            }
        } else {
            reader.read();
        }
        dashboard.invalidate();
    }

    private void finishObservation() {
        if (recording == null) return;
        if (!demo && reader != null && !recordingComplete) {
            if (safety == null || !safety.refreshNow()) {
                recording.interrupted = true;
                recordingComplete = true;
            } else {
                finalReadPending = true;
                reader.read(() -> {
                    if (recording == null || recordingComplete || !finalReadPending) return;
                    finalReadPending = false;
                    recordingComplete = true;
                    if (recording.points.size() < 3) recording.interrupted = true;
                    dashboard.invalidate();
                });
                handler.postDelayed(() -> {
                    if (!finalReadPending) return;
                    finalReadPending = false;
                    if (recording == null) return;
                    recording.interrupted = true;
                    recordingComplete = true;
                    error = "종료 시점의 현재값을 확인하지 못했습니다.";
                    dashboard.invalidate();
                }, 2000);
            }
            dashboard.invalidate();
            return;
        }
        recordingComplete = true;
        if (recording.points.size() < 3) recording.interrupted = true;
        dashboard.invalidate();
    }

    private void persistObservation() {
        if (recording == null || !recordingComplete) return;
        observations.add(recording);
        if (save()) {
            recording = null;
            screen = "compare";
        } else observations.remove(observations.size() - 1);
        dashboard.invalidate();
    }

    private void selectConditions() {
        String[] items = { "주차 후 첫 조작", "정비소에서 확인", "일상 중 조작" };
        new AlertDialog.Builder(this).setTitle("이번 조작 조건 · 사용자 확인")
                .setItems(items, (dialog, which) -> { condition = items[which]; dashboard.invalidate(); }).show();
    }

    private void calibrateClosedValue() {
        if (!vehicleReady || safety == null || !safety.refreshNow() || reader == null) {
            error = "차량 연결·주차 신호와 현재값이 필요합니다.";
            dashboard.invalidate();
            return;
        }
        reader.read(() -> {
            if (!vehicleReady || position == null || demo) return;
            final int value = position;
            new AlertDialog.Builder(this).setTitle("창문이 실제로 완전히 닫혀 있나요?")
                    .setMessage("방금 조회한 위치값은 " + value + "입니다. 눈으로 닫힘을 확인한 값만 기준으로 저장해 주세요.")
                    .setPositiveButton("닫힘 확인", (dialog, which) -> {
                        if (safety != null && safety.refreshNow()) { closedValue = value; dashboard.invalidate(); }
                    }).setNegativeButton("돌아가기", null).show();
        });
    }

    private void beginWithConfirmation(int stage) {
        new AlertDialog.Builder(this).setTitle(demo ? "가상 관찰을 재생할까요?" : "정상 조작 한 번을 관찰합니다")
                .setMessage(demo ? "이 시나리오는 가상 데이터입니다. 실제 차량 신호나 수리 결과가 아닙니다."
                        : "운전석 창문 주변이 비어 있나요? 닫기 방향으로 평소와 같은 스위치를 한 번만 조작해 주세요. 열기나 중간 조작을 했다면 관찰을 취소해 주세요. 이번 조건은 ‘" + condition + "’입니다.")
                .setPositiveButton("관찰 시작", (dialog, which) -> startObservation(stage))
                .setNegativeButton("돌아가기", null).show();
    }

    private void shareReport() {
        try {
            File file = new File(getCacheDir(), "recheck-report.txt");
            Files.write(file.toPath(), reportText().getBytes(StandardCharsets.UTF_8));
            Uri uri = Uri.parse("content://io.recheck.app.reports/report.txt");
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "같은 증상, 다시 확인 · 관찰 기록")
                    .putExtra(Intent.EXTRA_TEXT, reportText()).putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(ClipData.newUri(getContentResolver(), "관찰 기록", uri));
            startActivity(Intent.createChooser(send, "기록을 받을 앱 선택"));
        } catch (Exception exception) {
            Toast.makeText(this, "공유할 앱이 없습니다. 이 화면을 담당자에게 보여주세요.", Toast.LENGTH_LONG).show();
        }
    }

    private String reportText() {
        StringBuilder text = new StringBuilder("같은 증상, 다시 확인\n운전석 창문 관찰 기록\n\n");
        text.append(demo ? "출처: 시연용 모의 데이터. 실제 차량 관측이 아닙니다.\n" : "출처: Pleos Vehicle SDK가 제공한 창문 위치\n");
        SimpleDateFormat date = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA);
        for (Observation observation : observations) {
            text.append("\n").append(observation.stage).append(" · ").append(date.format(new Date(observation.capturedAt)))
                    .append("\n사용자 확인 조건: ").append(observation.conditions)
                    .append("\n관찰 결과: ").append(observation.result())
                    .append("\n수신 범위: ").append(observation.minimum).append("~").append(observation.maximum)
                    .append(" / 차주가 닫힘을 확인한 기준값: ").append(observation.closedValue)
                    .append("\n닫기 방향 한 번 조작 확인: ").append(observation.userConfirmed)
                    .append(" / 관찰 중단: ").append(observation.interrupted).append("\n수신값 (앱 경과시간 ms:값): ");
            for (Observation.Point point : observation.points) text.append("[").append(point.elapsedMs).append(":").append(point.rawPosition).append("] ");
            text.append("\n관찰점: ").append(observation.points.size()).append("개\n");
        }
        text.append("\n시간축은 앱의 수신 기준입니다. 창문 조작자·스위치 입력·동작 원인은 직접 측정하지 않았습니다.\n")
                .append("관측된 구간의 비교이며 수리 완료·고장 원인·안전성을 판정하지 않습니다.\n")
                .append("이름, 차량번호, VIN, 위치, 음성은 이 기록에 포함하지 않습니다.\n");
        return text.toString();
    }

    private final class Dashboard extends View {
        private final Paint paint = new Paint(3);
        private final List<Hit> hits = new ArrayList<>();
        private final int navy = Color.rgb(15, 35, 52);
        private final int teal = Color.rgb(0, 111, 111);
        private final int muted = Color.rgb(93, 112, 125);
        private final int amber = Color.rgb(172, 90, 19);
        private float scale = 1;
        private float offsetX;
        private float offsetY;

        Dashboard() { super(MainActivity.this); setContentDescription("같은 증상, 다시 확인. 차량 창문 관찰 앱"); }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            hits.clear();
            boolean portrait = getWidth() < getHeight();
            float width = portrait ? 900 : 1600;
            float height = portrait ? 1440 : 900;
            scale = Math.min(getWidth() / width, getHeight() / height);
            offsetX = (getWidth() - width * scale) / 2;
            offsetY = (getHeight() - height * scale) / 2;
            canvas.drawColor(Color.rgb(238, 243, 246));
            canvas.save();
            canvas.translate(offsetX, offsetY);
            canvas.scale(scale, scale);
            if (portrait) {
                canvas.scale(900f / 1600f, 900f / 1600f);
                scale *= 900f / 1600f;
            }
            drawHeader(canvas);
            if ("record".equals(screen)) drawRecord(canvas);
            else if ("compare".equals(screen)) drawComparison(canvas);
            else if ("report".equals(screen)) drawReport(canvas);
            else drawHome(canvas);
            drawFooter(canvas);
            canvas.restore();
        }

        private void drawHeader(Canvas canvas) {
            round(canvas, 0, 0, 1600, 116, 0, navy);
            text(canvas, "ReCheck", 44, 48, 23, Color.rgb(116, 221, 211), true);
            text(canvas, "같은 증상, 다시 확인", 44, 88, 32, Color.WHITE, true);
            text(canvas, demo ? "가상 데이터로 사용 흐름을 체험합니다" : "차량 신호의 읽기 연결을 확인합니다", 720, 67, 24, Color.rgb(207, 220, 228), false);
            button(canvas, 1260, 29, 1538, 87, demo ? "시연용 모의 데이터" : "Pleos 차량 읽기", demo ? Color.rgb(255, 225, 177) : Color.rgb(155, 226, 216), navy,
                    () -> new AlertDialog.Builder(MainActivity.this).setTitle("데이터 출처 선택")
                            .setItems(new String[] { "시연용 모의 데이터", "Pleos 차량 데이터 읽기" }, (dialog, index) -> setMode(index == 0)).show());
        }

        private void drawHome(Canvas canvas) {
            text(canvas, "정비 후에도, 내 차의 평소 조건에서.", 56, 183, 43, navy, true);
            text(canvas, "한 번의 관찰부터 이후 재발까지 같은 기록에 이어집니다.", 58, 231, 25, muted, false);
            round(canvas, 55, 276, 1080, 739, 24, Color.WHITE);
            text(canvas, "운전석 창문", 88, 327, 31, navy, true);
            text(canvas, "올라간 뒤 다시 내려오는 움직임", 88, 369, 25, muted, false);
            round(canvas, 841, 302, 1042, 353, 12, Color.rgb(231, 243, 240));
            text(canvas, observations.size() + "개 관찰 저장", 861, 336, 23, teal, true);
            float cardX = 86;
            for (int i = 0; i < 3; i++) {
                Observation observation = latest(i);
                round(canvas, cardX, 409, cardX + 300, 605, 14, Color.rgb(245, 248, 250));
                text(canvas, STAGES[i], cardX + 20, 447, 24, muted, true);
                if (observation == null) {
                    text(canvas, "관찰 전", cardX + 20, 515, 27, Color.rgb(124, 140, 151), true);
                    line(canvas, cardX + 20, 550, cardX + 265, 550, Color.rgb(212, 222, 228), 3);
                } else {
                    text(canvas, compactResult(observation), cardX + 20, 489, 25, resultColor(observation), true);
                    chart(canvas, observation, cardX + 22, 518, cardX + 270, 581, resultColor(observation), false);
                }
                cardX += 320;
            }
            int next = Math.min(2, observations.size());
            button(canvas, 87, 644, 625, 708, observations.isEmpty() ? "첫 증상 기록하기" : STAGES[next] + " 관찰 이어가기", teal, Color.WHITE, () -> beginWithConfirmation(next));
            button(canvas, 650, 644, 1042, 708, "기록 나란히 보기", Color.rgb(231, 239, 244), navy, () -> { screen = "compare"; invalidate(); });
            round(canvas, 1110, 276, 1543, 739, 24, Color.rgb(222, 235, 238));
            text(canvas, "평소처럼 한 번", 1140, 328, 29, navy, true);
            text(canvas, "순정 스위치로 조작합니다.", 1140, 378, 24, navy, false);
            text(canvas, "차량이 보고한 위치를", 1140, 416, 24, navy, false);
            text(canvas, "앱이 읽어 기록합니다.", 1140, 454, 24, navy, false);
            text(canvas, "이번 조건 · 사용자 확인", 1140, 524, 21, muted, true);
            button(canvas, 1138, 545, 1514, 608, condition, Color.WHITE, navy, MainActivity.this::selectConditions);
            if (demo) {
                text(canvas, "실제 차량 신호가 아닙니다.", 1140, 654, 23, amber, true);
                button(canvas, 1138, 674, 1514, 719, "시연 기록 초기화", Color.rgb(222, 235, 238), muted, () -> new AlertDialog.Builder(MainActivity.this)
                        .setTitle("모의 기록을 초기화할까요?").setPositiveButton("초기화", (dialog, which) -> { observations.clear(); save(); invalidate(); }).setNegativeButton("돌아가기", null).show());
            } else {
                text(canvas, parked ? "P·정지 확인" : "주차 신호 확인 전", 1140, 647, 22, parked ? teal : amber, true);
                button(canvas, 1138, 672, 1514, 720, closedValue == null ? "실제 닫힌 위치 기준 저장" : "닫힌 기준값 " + closedValue, Color.WHITE, navy, MainActivity.this::calibrateClosedValue);
            }
        }

        private void drawRecord(Canvas canvas) {
            text(canvas, recordingComplete ? "이번에 관측한 움직임" : "평소처럼 창문을 한 번 조작해 주세요", 56, 179, 41, navy, true);
            text(canvas, STAGES[selectedStage] + "  ·  운전석 창문  ·  " + condition, 58, 224, 25, muted, false);
            round(canvas, 55, 267, 1120, 731, 24, Color.WHITE);
            String state = recordingComplete ? (recording == null ? "관찰 전" : recording.result()) : "위치 변화를 관찰하고 있습니다";
            text(canvas, state, 93, 321, 31, recordingComplete && recording != null ? resultColor(recording) : teal, true);
            text(canvas, demo ? "가상 시나리오 재생" : "수신 시점 기준 · 차량이 보고한 값", 94, 363, 23, muted, false);
            if (recording != null && !recording.points.isEmpty()) chart(canvas, recording, 118, 402, 1048, 623, recording.interrupted ? amber : teal, true);
            else text(canvas, "새로운 위치값을 기다립니다", 393, 502, 28, muted, false);
            text(canvas, "관찰점 " + (recording == null ? 0 : recording.points.size()) + "개", 91, 688, 23, muted, true);
            text(canvas, recordingComplete ? "이번 시도의 결과입니다." : "창문에 저항을 주거나 반복 조작하지 마세요.", 355, 688, 24, muted, false);
            round(canvas, 1152, 267, 1543, 731, 24, Color.rgb(222, 235, 238));
            text(canvas, "움직임을 보존합니다", 1180, 322, 28, navy, true);
            text(canvas, "정비 전과 후의 기록을", 1180, 377, 23, navy, false);
            text(canvas, "같은 축에서 비교하세요.", 1180, 416, 23, navy, false);
            if (recordingComplete) button(canvas, 1180, 520, 1514, 589, "이번 관찰 저장", teal, Color.WHITE, MainActivity.this::persistObservation);
            else button(canvas, 1180, 520, 1514, 589, "관찰 마치기", teal, Color.WHITE, MainActivity.this::finishObservation);
            button(canvas, 1180, 613, 1514, 674, "관찰 취소", Color.WHITE, navy, () -> {
                handler.removeCallbacksAndMessages(null);
                recording = null;
                screen = "home";
                invalidate();
            });
            if (demo) button(canvas, 62, 757, 451, 810, "연결 끊김 예시 보기", Color.rgb(233, 225, 211), amber, () -> {
                handler.removeCallbacksAndMessages(null);
                if (recording == null) recording = example(selectedStage, true);
                recording.interrupted = true;
                recordingComplete = true;
                error = "이번 관찰은 불완전합니다. 정상 결과로 집계되지 않습니다.";
                invalidate();
            });
        }

        private void drawComparison(Canvas canvas) {
            text(canvas, "같은 창문. 세 번의 관찰.", 56, 180, 43, navy, true);
            text(canvas, "정비 전·인수할 때·일상의 움직임을 같은 기록에서 확인합니다.", 58, 228, 25, muted, false);
            for (int i = 0; i < 3; i++) {
                float x = 55 + i * 503;
                Observation observation = latest(i);
                round(canvas, x, 270, x + 478, 658, 24, Color.WHITE);
                text(canvas, STAGES[i], x + 28, 319, 27, muted, true);
                text(canvas, observation == null ? "관찰 전" : compactResult(observation), x + 28, 369, 31, observation == null ? muted : resultColor(observation), true);
                if (observation != null) {
                    chart(canvas, observation, x + 50, 407, x + 424, 540, resultColor(observation), true);
                    text(canvas, observation.points.size() + "개 수신값 · " + observation.conditions, x + 28, 591, 21, muted, false);
                    Observation baseline = latest(0);
                    String compare = i == 0 ? "비교 기준 기록" : observation.comparableTo(baseline) ? "조건 표기·시작 위치 유사" : "조건·데이터 확인 후 비교";
                    text(canvas, compare, x + 28, 630, 22, i == 0 || observation.comparableTo(baseline) ? teal : amber, true);
                } else {
                    text(canvas, "평소처럼 한 번 조작한 뒤", x + 28, 446, 24, muted, false);
                    text(canvas, "기록을 이어주세요.", x + 28, 484, 24, muted, false);
                }
            }
            boolean recurrence = latest(0) != null && latest(2) != null
                    && latest(0).result().contains("되돌아") && latest(2).result().contains("되돌아")
                    && latest(2).comparableTo(latest(0));
            text(canvas, recurrence ? "같은 움직임이 다시 보입니다. 이전 정비 건과 함께 보여주세요." : "관측한 시도만 남깁니다. 확인 전인 조건도 그대로 보입니다.", 62, 710, 27, navy, true);
            button(canvas, 59, 750, 449, 814, "증상 카드로 돌아가기", Color.rgb(225, 235, 241), navy, () -> { screen = "home"; invalidate(); });
            int next = Math.min(2, observations.size());
            button(canvas, 479, 750, 1036, 814, observations.size() >= 3 ? "일상 재확인 한 번 더" : STAGES[next] + " 관찰 이어가기", teal, Color.WHITE, () -> beginWithConfirmation(next));
            button(canvas, 1065, 750, 1542, 814, "담당자에게 보여줄 기록", navy, Color.WHITE, () -> { screen = "report"; invalidate(); });
        }

        private void drawReport(Canvas canvas) {
            text(canvas, "이전 설명부터 다시 시작하지 않도록.", 56, 179, 42, navy, true);
            text(canvas, "이 증상 기록의 공유 여부는 차주가 정합니다.", 58, 224, 25, muted, false);
            round(canvas, 55, 266, 1028, 740, 24, Color.WHITE);
            text(canvas, "운전석 창문 · 동일 증상 관찰", 88, 316, 29, navy, true);
            text(canvas, demo ? "시연용 모의 데이터 / 실제 정비 사례 아님" : "Pleos Vehicle SDK / 앱 수신 기준", 88, 357, 24, demo ? amber : teal, true);
            for (int i = 0; i < 3; i++) {
                float y = 410 + i * 93;
                Observation observation = latest(i);
                text(canvas, STAGES[i], 91, y, 25, muted, true);
                text(canvas, observation == null ? "관찰 전" : observation.result(), 327, y, 27, observation == null ? muted : resultColor(observation), true);
                if (observation != null) text(canvas, observation.conditions + " · " + observation.points.size() + "개 값 수신", 328, y + 36, 21, muted, false);
                line(canvas, 89, y + 56, 993, y + 56, Color.rgb(225, 233, 238), 2);
            }
            text(canvas, "차량번호·VIN·위치·음성 없이 보관", 90, 716, 23, teal, true);
            round(canvas, 1061, 266, 1542, 740, 24, Color.rgb(222, 235, 238));
            text(canvas, "이 기록으로 함께 확인해요", 1091, 322, 29, navy, true);
            text(canvas, "어떤 때 움직임이 나타났는지", 1091, 381, 25, navy, false);
            text(canvas, "정비 후 어떤 시도를 관찰했는지", 1091, 424, 25, navy, false);
            text(canvas, "아직 확인하지 못한 조건은 뭔지", 1091, 467, 25, navy, false);
            text(canvas, "수리 결과와 원인은", 1091, 550, 23, muted, false);
            text(canvas, "담당자와 함께 확인해 주세요.", 1091, 588, 23, muted, false);
            button(canvas, 1091, 642, 1512, 710, "공유할 앱 선택", teal, Color.WHITE, MainActivity.this::shareReport);
            button(canvas, 59, 758, 533, 814, "세 관찰 다시 비교", navy, Color.WHITE, () -> { screen = "compare"; invalidate(); });
            button(canvas, 565, 758, 1038, 814, "증상 카드로 돌아가기", Color.rgb(225, 235, 241), navy, () -> { screen = "home"; invalidate(); });
        }

        private void drawFooter(Canvas canvas) {
            text(canvas, error.isEmpty() ? connection : error, 58, 859, 21, error.isEmpty() ? muted : amber, false);
            text(canvas, "ReCheck  0.1  ·  관찰한 구간의 기록", 1184, 859, 20, muted, false);
        }

        private Observation latest(int stage) {
            for (int i = observations.size() - 1; i >= 0; i--) if (observations.get(i).stage.equals(STAGES[stage])) return observations.get(i);
            return null;
        }

        private int resultColor(Observation observation) {
            return observation.result().contains("되돌아") || observation.interrupted ? amber : teal;
        }

        private String compactResult(Observation observation) {
            if (observation.result().contains("되돌아")) return "다시 내려옴";
            if (observation.result().contains("닫힘 위치")) return "이번에는 닫힘";
            return observation.result();
        }

        private void chart(Canvas canvas, Observation observation, float left, float top, float right, float bottom, int color, boolean labels) {
            for (int i = 0; i <= 2; i++) {
                float y = top + (bottom - top) * i / 2;
                line(canvas, left, y, right, y, Color.rgb(220, 230, 235), 2);
            }
            if (labels) {
                text(canvas, "닫힘", left - 30, top - 13, 19, muted, false);
                text(canvas, "시작", left - 15, bottom + 32, 19, muted, false);
                text(canvas, "수신 순서", right - 95, bottom + 32, 19, muted, false);
            }
            if (observation.points.isEmpty()) return;
            int count = Math.max(10, observation.points.size());
            Path path = new Path();
            for (int i = 0; i < observation.points.size(); i++) {
                Observation.Point point = observation.points.get(i);
                float x = left + (right - left) * i / (count - 1f);
                float y = bottom - (bottom - top) * observation.height(point);
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(6);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
            Observation.Point last = observation.points.get(observation.points.size() - 1);
            float x = left + (right - left) * (observation.points.size() - 1) / (count - 1f);
            float y = bottom - (bottom - top) * observation.height(last);
            canvas.drawCircle(x, y, 7, paint);
        }

        private void text(Canvas canvas, String value, float x, float y, float size, int color, boolean bold) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            paint.setTextSize(size);
            paint.setTypeface(bold ? android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD) : android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
            canvas.drawText(value, x, y, paint);
        }

        private void round(Canvas canvas, float left, float top, float right, float bottom, float radius, int color) {
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);
        }

        private void line(Canvas canvas, float left, float top, float right, float bottom, int color, float width) {
            paint.setColor(color);
            paint.setStrokeWidth(width);
            canvas.drawLine(left, top, right, bottom, paint);
        }

        private void button(Canvas canvas, float left, float top, float right, float bottom, String label, int background, int foreground, Runnable action) {
            round(canvas, left, top, right, bottom, 13, background);
            paint.setTextSize(25);
            paint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
            float textWidth = paint.measureText(label);
            text(canvas, label, left + (right - left - textWidth) / 2, top + (bottom - top) / 2 + 9, 25, foreground, true);
            hits.add(new Hit(new RectF(left, top, right, bottom), action));
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                float x = (event.getX() - offsetX) / scale;
                float y = (event.getY() - offsetY) / scale;
                for (Hit hit : hits) {
                    if (hit.bounds.contains(x, y)) { performClick(); hit.action.run(); return true; }
                }
            }
            return true;
        }

        @Override public boolean performClick() { super.performClick(); return true; }
    }

    private static final class Hit {
        final RectF bounds;
        final Runnable action;
        Hit(RectF bounds, Runnable action) { this.bounds = bounds; this.action = action; }
    }
}
