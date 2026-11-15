package io.recheck.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
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
        dashboard.refresh();
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
            dashboard.refresh();
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
        dashboard.refresh();
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
                dashboard.refresh();
            }));
            reader = new VehicleReader(this, new VehicleReader.Listener() {
                @Override public void onReady(int low, int high) {
                    minimum = low;
                    maximum = high;
                    vehicleReady = true;
                    connection = "Pleos Vehicle 2.3.2 · 운전석 창 위치 읽기 연결";
                    dashboard.refresh();
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
                    dashboard.refresh();
                }
                @Override public void onError(String message) {
                    error = message;
                    vehicleReady = false;
                    if (recording != null && !recordingComplete) {
                        recording.interrupted = true;
                        recordingComplete = true;
                    }
                    dashboard.refresh();
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
                    dashboard.refresh();
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
        if (dashboard != null) dashboard.refresh();
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
        if (loadFailed) { dashboard.refresh(); return; }
        if (!demo && safety != null) parked = safety.refreshNow();
        if (!demo && (!parked || !vehicleReady || closedValue == null)) {
            error = "연결·P 상태와 닫힌 위치 기준을 먼저 확인해 주세요.";
            dashboard.refresh();
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
                    dashboard.refresh();
                }, i * 500L);
            }
        } else {
            reader.read();
        }
        dashboard.refresh();
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
                    dashboard.refresh();
                });
                handler.postDelayed(() -> {
                    if (!finalReadPending) return;
                    finalReadPending = false;
                    if (recording == null) return;
                    recording.interrupted = true;
                    recordingComplete = true;
                    error = "종료 시점의 현재값을 확인하지 못했습니다.";
                    dashboard.refresh();
                }, 2000);
            }
            dashboard.refresh();
            return;
        }
        recordingComplete = true;
        if (recording.points.size() < 3) recording.interrupted = true;
        dashboard.refresh();
    }

    private void persistObservation() {
        if (recording == null || !recordingComplete) return;
        observations.add(recording);
        if (save()) {
            recording = null;
            screen = "compare";
        } else observations.remove(observations.size() - 1);
        dashboard.refresh();
    }

    private void selectConditions() {
        String[] items = { "주차 후 첫 조작", "정비소에서 확인", "일상 중 조작" };
        new AlertDialog.Builder(this).setTitle("이번 조작 조건 · 사용자 확인")
                .setItems(items, (dialog, which) -> { condition = items[which]; dashboard.refresh(); }).show();
    }

    private void calibrateClosedValue() {
        if (!vehicleReady || safety == null || !safety.refreshNow() || reader == null) {
            error = "차량 연결·주차 신호와 현재값이 필요합니다.";
            dashboard.refresh();
            return;
        }
        reader.read(() -> {
            if (!vehicleReady || position == null || demo) return;
            final int value = position;
            new AlertDialog.Builder(this).setTitle("창문이 실제로 완전히 닫혀 있나요?")
                    .setMessage("방금 조회한 위치값은 " + value + "입니다. 눈으로 닫힘을 확인한 값만 기준으로 저장해 주세요.")
                    .setPositiveButton("닫힘 확인", (dialog, which) -> {
                        if (safety != null && safety.refreshNow()) { closedValue = value; dashboard.refresh(); }
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


    private Observation detail;
    private String returnScreen = "compare";

    private void show(String nextScreen) {
        screen = nextScreen;
        dashboard.refresh();
    }

    private void showDetail(Observation observation) {
        detail = observation;
        returnScreen = screen;
        show("detail");
    }

    private void cancelObservation() {
        handler.removeCallbacksAndMessages(null);
        recording = null;
        finalReadPending = false;
        error = "";
        show("home");
    }

    @Override public void onBackPressed() {
        switch (screen) {
            case "detail": show(returnScreen); break;
            case "record":
                new AlertDialog.Builder(this).setTitle("이번 기록을 취소할까요?")
                        .setPositiveButton("취소하기", (dialog, which) -> cancelObservation())
                        .setNegativeButton("계속 보기", null).show();
                break;
            case "report": show("compare"); break;
            case "compare": show("home"); break;
            default: super.onBackPressed();
        }
    }

    private Observation latest(int stage) {
        for (int i = observations.size() - 1; i >= 0; i--) {
            if (STAGES[stage].equals(observations.get(i).stage)) return observations.get(i);
        }
        return null;
    }

    private int nextStage() {
        for (int i = 0; i < STAGES.length; i++) if (latest(i) == null) return i;
        return STAGES.length - 1;
    }

    private void resetDemo() {
        new AlertDialog.Builder(this).setTitle("시연 기록을 초기화할까요?")
                .setPositiveButton("초기화", (dialog, which) -> {
                    List<Observation> previous = new ArrayList<>(observations);
                    observations.clear();
                    if (!save()) observations.addAll(previous);
                    dashboard.refresh();
                })
                .setNegativeButton("돌아가기", null).show();
    }

    private static final int BACKGROUND = 0xff10151b;
    private static final int SURFACE = 0xff181f26;
    private static final int CONTROL = 0xff242e37;
    private static final int STROKE = 0xff35424d;
    private static final int TEXT = 0xfff1f6f7;
    private static final int MUTED = 0xffb3c3cb;
    private static final int MINT = 0xffa4f5c9;
    private static final int AMBER = 0xffffcd91;

    private static String result(Observation observation) {
        if (observation == null) return "기록 없음";
        if (observation.result().contains("되돌아")) return "다시 내려옴";
        if (observation.result().equals("닫힘 위치 관측")) return "닫힘 확인";
        return observation.result();
    }

    private static int resultColor(Observation observation) {
        if (observation == null) return MUTED;
        return observation.result().equals("닫힘 위치 관측") ? MINT : AMBER;
    }

    private final class Dashboard extends FrameLayout {
        private final Typeface normal = Typeface.create("sans-serif", Typeface.NORMAL);
        private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        private final SimpleDateFormat date = new SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA);
        private FrameLayout scene;
        private WindowIllustration window;
        private TextView received;
        private float scale = 1f;
        private boolean stacked;
        private String displayedState = "";

        Dashboard() {
            super(MainActivity.this);
            setBackgroundColor(BACKGROUND);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            displayedState = "";
            post(this::refresh);
        }

        void refresh() {
            if (getWidth() == 0 || getHeight() == 0) return;
            String state = screen + "|" + demo + "|" + parked + "|" + vehicleReady + "|" + closedValue
                    + "|" + connection + "|" + condition + "|" + observations.size() + "|" + error
                    + "|" + (recording == null ? "" : recording.id) + "|" + recordingComplete
                    + "|" + finalReadPending + "|" + loadFailed + "|" + (detail == null ? "" : detail.id);
            if (!state.equals(displayedState)) {
                displayedState = state;
                render();
            }
            if (window != null) window.update(glassHeight(), "record".equals(screen) && !recordingComplete);
            if (received != null && recording != null) {
                String measurement = recording.points.size() + "개 위치값 수신";
                if (!measurement.contentEquals(received.getText())) received.setText(measurement);
            }
        }

        private float glassHeight() {
            if ("record".equals(screen) && recording != null && !recording.points.isEmpty())
                return recording.height(recording.points.get(recording.points.size() - 1));
            if (!demo && position != null && closedValue != null && maximum > minimum)
                return 1f - Math.min(1f, Math.abs((float) position - closedValue) / ((float) maximum - minimum));
            return .38f;
        }

        private void render() {
            removeAllViews();
            window = null;
            received = null;
            stacked = getWidth() < getHeight() * 1.25f;
            scale = stacked ? getWidth() / 1000f : Math.min(getWidth() / 1600f, getHeight() / 900f);
            scene = new FrameLayout(MainActivity.this);
            if (stacked) {
                ScrollView scroll = new ScrollView(MainActivity.this);
                scroll.setFillViewport(true);
                scroll.setVerticalScrollBarEnabled(false);
                addView(scroll, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
                scroll.addView(scene, new ScrollView.LayoutParams(px(1000), px(1620)));
                scene.setMinimumHeight(px(1620));
            } else {
                FrameLayout.LayoutParams bounds = new FrameLayout.LayoutParams(px(1600), px(900), Gravity.CENTER);
                addView(scene, bounds);
            }
            header();
            boolean actionsFirst = stacked && ("home".equals(screen) || "record".equals(screen));
            FrameLayout main = panel(48, actionsFirst ? 862 : 112, 944);
            FrameLayout actions = panel(stacked ? 48 : 1016, stacked && !actionsFirst ? 862 : 112, stacked ? 904 : 536);
            if (stacked) {
                // Keep the diagram's layout units while reserving the right edge for scrolling.
                LayoutParams bounds = (LayoutParams) main.getLayoutParams();
                bounds.width = px(904);
                main.setLayoutParams(bounds);
            }
            switch (screen) {
                case "record": record(main, actions); break;
                case "compare": compare(main, actions); break;
                case "report": report(main, actions); break;
                case "detail": movement(main, actions); break;
                default: home(main, actions); break;
            }
        }

        private void header() {
            int width = stacked ? 1000 : 1600;
            divider(scene, 0, 87, width);
            FrameLayout mark = new FrameLayout(MainActivity.this);
            mark.setBackground(shape(MINT, 12, 0));
            put(scene, mark, 48, 20, 48, 48);
            label(mark, "↗", 0, 0, 48, 46, 30, BACKGROUND, true).setGravity(Gravity.CENTER);
            label(scene, "ReCheck", 112, 17, 252, 54, 32, TEXT, true);
            boolean inObservation = "record".equals(screen) || ("detail".equals(screen) && "record".equals(returnScreen));
            int navigationLeft = stacked ? 326 : 595;
            if (!inObservation) {
                nav("기록", navigationLeft, screen.equals("home"), () -> show("home"));
                nav("비교", navigationLeft + 120, screen.equals("compare") || screen.equals("report") || screen.equals("detail"), () -> show("compare"));
            }
            if (!demo && !stacked)
                label(scene, parked ? "P   주차 확인" : "주차 확인 전", 1058, 17, 218, 54, 23, parked ? MINT : MUTED, true);
            Runnable sources = inObservation ? null : () -> new AlertDialog.Builder(MainActivity.this)
                    .setTitle("데이터 출처")
                    .setItems(new String[] { "시연용 모의 데이터", "Pleos 차량 데이터 읽기" }, (dialog, index) -> setMode(index == 0))
                    .show();
            button(scene, demo ? "시연 모드" : "Pleos 차량", width - 258, 14, 210, 60, demo ? AMBER : MINT, CONTROL, sources);
        }

        private void nav(String title, int x, boolean current, Runnable task) {
            button(scene, title, x, 12, 114, 64, current ? MINT : MUTED, BACKGROUND, task);
            if (current) {
                View bar = new View(MainActivity.this);
                bar.setBackground(shape(MINT, 4, 0));
                put(scene, bar, x + 29, 84, 56, 4);
            }
        }

        private void home(FrameLayout main, FrameLayout actions) {
            label(main, "운전석 창문", 40, 35, 800, 86, 68, TEXT, true);
            label(main, "닫다가 다시 내려오는 증상", 44, 127, 800, 45, 30, MUTED, false);
            illustration(main, 174, 470);
            label(main, demo ? "시연용 모의 데이터" : connection, 44, 650, 805, 52, 23, demo ? AMBER : MUTED, false);

            int next = nextStage();
            int width = contentWidth();
            label(actions, "다음 기록", 38, 36, width, 40, 25, MINT, true);
            label(actions, STAGES[next], 38, 84, width, 68, 53, TEXT, true);
            divider(actions, 38, 190, width);
            label(actions, "조작 조건", 38, 226, width, 42, 25, MUTED, false);
            button(actions, condition + "   ⌄", 38, 278, width, 70, TEXT, CONTROL, MainActivity.this::selectConditions);
            String guide;
            String callToAction = "기록 시작";
            Runnable action = () -> beginWithConfirmation(next);
            if (demo) {
                guide = "가상 창문 움직임을 재생합니다.";
            } else if (!parked || !vehicleReady) {
                guide = vehicleReady ? "P에 주차하고 정차해 주세요." : connection;
                action = null;
            } else if (closedValue == null) {
                guide = "창문을 완전히 닫아 주세요.";
                callToAction = "닫힘 기준 확인";
                action = MainActivity.this::calibrateClosedValue;
            } else {
                guide = "P 주차 확인 · 닫힘 기준 준비됨";
            }
            message(actions, guide, 404, 103);
            if (loadFailed) action = null;
            primary(actions, callToAction, action);
            if (demo && !observations.isEmpty()) secondary(actions, "시연 기록 초기화", MainActivity.this::resetDemo);
            else if (!demo && closedValue != null) secondary(actions, "닫힘 기준 다시 확인", MainActivity.this::calibrateClosedValue);
        }

        private void record(FrameLayout main, FrameLayout actions) {
            label(main, recordingComplete ? "이번 관찰" : "운전석 창문", 40, 34, 800, 64, 49, TEXT, true);
            label(main, STAGES[selectedStage] + "  ·  " + condition, 44, 103, 805, 45, 27, MUTED, false);
            illustration(main, 160, 470);
            received = label(main, "", 44, 641, 805, 49, 23, MUTED, false);

            int width = contentWidth();
            String state = recordingComplete ? "관찰 결과" : finalReadPending ? "종료 확인 중" : "●  기록 중";
            label(actions, state, 38, 36, width, 40, 25, recordingComplete ? MUTED : MINT, true);
            if (recordingComplete) {
                label(actions, result(recording), 38, 86, width, 83, 51, resultColor(recording), true);
                String outcome = recording == null ? "" : result(recording).equals("다시 내려옴")
                        ? "창문이 닫히는 쪽으로 움직인 뒤 다시 열리는 쪽으로 움직였어요."
                        : result(recording).equals("닫힘 확인") ? "이번 조작에서 닫힘 기준에 도달했어요."
                        : result(recording).equals("관찰 불충분") ? "이번 움직임을 비교하기에 위치값이 충분하지 않아요."
                        : "이번 조작에서 닫힘 기준을 확인하지 못했어요.";
                label(actions, outcome, 38, 181, width, 115, 28, MUTED, false);
                if (recording != null && !recording.points.isEmpty()) {
                    button(actions, "움직임 자세히  ›", 38, 339, width, 68, TEXT, CONTROL, () -> showDetail(recording));
                }
                message(actions, "", 416, 92);
                primary(actions, "기록 저장", MainActivity.this::persistObservation);
            } else {
                label(actions, demo ? "가상 창문\n재생 중" : "창문을 닫아\n주세요", 38, 87, width, 158, 56, TEXT, true);
                label(actions, demo ? "움직임이 끝나면 결과가 표시됩니다." : "순정 스위치로 한 번만 조작해 주세요.", 38, 272, width, 105, 29, MUTED, false);
                message(actions, "", 425, 84);
                primary(actions, demo ? "재생 중" : finalReadPending ? "마무리 중" : "기록 마치기",
                        demo || finalReadPending ? null : MainActivity.this::finishObservation);
            }
            secondary(actions, "기록 취소", MainActivity.this::cancelObservation);
        }

        private void compare(FrameLayout main, FrameLayout actions) {
            Observation first = latest(0);
            Observation recent = latest(2);
            boolean recurred = recent != null && recent.comparableTo(first)
                    && "다시 내려옴".equals(result(first)) && "다시 내려옴".equals(result(recent));
            label(main, "기록 비교", 40, 34, 800, 74, 55, TEXT, true);
            label(main, recurred ? "정비 전과 같은 움직임이 다시 보였어요." : "운전석 창문", 43, 114, 813, 54, 29,
                    recurred ? AMBER : MUTED, false);
            stageRows(main, true);
            label(main, recurred ? "조작 조건과 시작 위치가 비슷한 두 기록입니다." : "", 44, 644, 821, 44, 23, MUTED, false);

            int width = contentWidth();
            int next = nextStage();
            label(actions, "다음 기록", 38, 36, width, 40, 25, MINT, true);
            label(actions, STAGES[next], 38, 85, width, 68, 48, TEXT, true);
            divider(actions, 38, 190, width);
            label(actions, observations.isEmpty() ? "창문 움직임을 기록하면 여기에 결과가 표시됩니다."
                    : "상담에 보여줄 기록을 한 화면에서 열 수 있어요.", 38, 223, width, 115, 30, MUTED, false);
            if (!observations.isEmpty())
                button(actions, "상담용 기록  ›", 38, 354, width, 72, TEXT, CONTROL, () -> show("report"));
            primary(actions, "기록 화면으로", () -> show("home"));
        }

        private void stageRows(FrameLayout main, boolean selectable) {
            for (int i = 0; i < STAGES.length; i++) {
                Observation observation = latest(i);
                int y = 202 + 135 * i;
                FrameLayout row = new FrameLayout(MainActivity.this);
                row.setBackground(shape(CONTROL, 18, 0));
                put(main, row, 40, y, stacked ? 824 : 864, 111);
                label(row, STAGES[i], 28, 11, 345, 51, 33, TEXT, true);
                label(row, observation == null ? "" : date.format(new Date(observation.capturedAt)), 30, 66, 370, 29, 21, MUTED, false);
                label(row, result(observation), 388, 9, 324, 86, 34, resultColor(observation), true);
                if (selectable && observation != null) {
                    label(row, "›", stacked ? 744 : 789, 11, 46, 84, 46, MUTED, false);
                    row.setForeground(new RippleDrawable(ColorStateList.valueOf(0x29ffffff), null, shape(Color.WHITE, 18, 0)));
                    row.setContentDescription(STAGES[i] + " " + result(observation) + ", 움직임 자세히");
                    row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                    row.setOnClickListener(view -> showDetail(observation));
                    row.setFocusable(true);
                }
            }
        }

        private void report(FrameLayout main, FrameLayout actions) {
            label(main, "상담용 기록", 40, 34, 800, 74, 55, TEXT, true);
            label(main, demo ? "운전석 창문 · 시연용 모의 데이터" : "운전석 창문 · Pleos 차량 데이터", 43, 114, 813, 54, 28,
                    demo ? AMBER : MINT, false);
            stageRows(main, false);
            label(main, "차량번호 · 위치 · 음성 미포함", 44, 645, 800, 44, 23, MUTED, false);

            int width = contentWidth();
            label(actions, "기록 공유", 38, 37, width, 67, 49, TEXT, true);
            divider(actions, 38, 151, width);
            label(actions, "조작 조건과 위치값이 포함된 텍스트 파일을 공유합니다.", 38, 195, width, 121, 31, MUTED, false);
            primary(actions, "파일 공유", MainActivity.this::shareReport);
            secondary(actions, "비교로 돌아가기", () -> show("compare"));
        }

        private void movement(FrameLayout main, FrameLayout actions) {
            if (detail == null) {
                show("compare");
                return;
            }
            label(main, "움직임 자세히", 40, 34, 800, 76, 55, TEXT, true);
            label(main, detail.stage + "  ·  " + result(detail), 44, 115, 799, 49, 29, resultColor(detail), false);
            put(main, new MovementChart(MainActivity.this, detail, resultColor(detail)), 42, 185, stacked ? 820 : 860, 479);
            int width = contentWidth();
            label(actions, "관찰 조건", 38, 36, width, 42, 25, MINT, true);
            label(actions, detail.conditions, 38, 88, width, 76, 42, TEXT, true);
            divider(actions, 38, 191, width);
            label(actions, detail.points.size() + "개 위치값 수신", 38, 221, width, 62, 29, MUTED, false);
            if (!"record".equals(returnScreen) && !STAGES[0].equals(detail.stage)) {
                label(actions, detail.comparableTo(latest(0)) ? "정비 전과 비슷한 조건" : "정비 전과 비교 조건 확인 필요",
                        38, 308, width, 113, 28, MUTED, false);
            }
            primary(actions, "돌아가기", () -> show(returnScreen));
        }

        private void illustration(FrameLayout panel, int top, int height) {
            window = new WindowIllustration(MainActivity.this);
            put(panel, window, 16, top, stacked ? 870 : 912, height);
        }

        private int contentWidth() { return stacked ? 828 : 460; }

        private void message(FrameLayout panel, String guide, int top, int height) {
            label(panel, error.isEmpty() ? guide : error, 38, top, contentWidth(), height, 27,
                    error.isEmpty() ? MUTED : AMBER, false);
        }

        private void primary(FrameLayout panel, String text, Runnable task) {
            button(panel, text, 38, 534, contentWidth(), 86, task == null ? MUTED : BACKGROUND,
                    task == null ? CONTROL : MINT, task);
        }

        private void secondary(FrameLayout panel, String text, Runnable task) {
            button(panel, text, 38, 638, contentWidth(), 64, MUTED, SURFACE, task);
        }

        private FrameLayout panel(int left, int top, int width) {
            FrameLayout panel = new FrameLayout(MainActivity.this);
            panel.setBackground(shape(SURFACE, 32, STROKE));
            put(scene, panel, left, top, width, 734);
            return panel;
        }

        private void divider(FrameLayout parent, int left, int top, int width) {
            View line = new View(MainActivity.this);
            line.setBackgroundColor(STROKE);
            put(parent, line, left, top, width, 1);
        }

        private TextView label(FrameLayout parent, String text, int left, int top, int width, int height, int size, int color, boolean bold) {
            TextView view = new TextView(MainActivity.this);
            style(view, text, size, color, bold);
            put(parent, view, left, top, width, height);
            return view;
        }

        private TextView button(FrameLayout parent, String text, int left, int top, int width, int height, int color, int fill, Runnable task) {
            TextView view = new TextView(MainActivity.this) {
                @Override public CharSequence getAccessibilityClassName() { return "android.widget.Button"; }
            };
            style(view, text, height >= 80 ? 33 : 26, color, true);
            view.setGravity(Gravity.CENTER);
            GradientDrawable background = shape(fill, 16, 0);
            if (task == null) {
                view.setBackground(background);
                view.setEnabled(false);
            } else {
                view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2fffffff), background, null));
                view.setOnClickListener(clicked -> task.run());
                view.setFocusable(true);
            }
            put(parent, view, left, top, width, height);
            return view;
        }

        private void style(TextView view, String text, int size, int color, boolean bold) {
            view.setIncludeFontPadding(false);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(size));
            view.setTextColor(color);
            view.setTypeface(bold ? medium : normal);
            view.setText(text);
        }

        private GradientDrawable shape(int fill, int radius, int stroke) {
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(px(radius));
            shape.setColor(fill);
            if (stroke != 0) shape.setStroke(Math.max(1, px(1)), stroke);
            return shape;
        }

        private void put(FrameLayout parent, View view, int left, int top, int width, int height) {
            FrameLayout.LayoutParams bounds = new FrameLayout.LayoutParams(px(width), px(height));
            bounds.leftMargin = px(left);
            bounds.topMargin = px(top);
            parent.addView(view, bounds);
        }

        private int px(float units) { return Math.round(units * scale); }
    }
}
