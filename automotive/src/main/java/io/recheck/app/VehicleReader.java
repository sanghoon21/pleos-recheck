package io.recheck.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import ai.pleos.playground.vehicle.Vehicle;
import ai.pleos.playground.vehicle.api.Window;
import ai.pleos.playground.vehicle.api.model.area.WindowArea;
import ai.pleos.playground.vehicle.api.model.capability.AccessType;
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds;
import ai.pleos.playground.vehicle.constant.area.WindowValue;
import ai.pleos.playground.vehicle.listener.WindowPositionListener;
import kotlin.Unit;

/** Read-only official Pleos Vehicle 2.3.2 adapter. No control API is called. */
public final class VehicleReader {
    public interface Listener {
        void onReady(int minimum, int maximum);
        void onPosition(int position);
        void onError(String message);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Vehicle vehicle;
    private final WindowArea area = new WindowArea(WindowValue.ROW_1_LEFT);
    private final Listener listener;
    private Window window;
    private WindowPositionListener updates;
    private boolean closed;
    private boolean ready;
    private long startedAt;
    private int minimum;
    private int maximum;

    public VehicleReader(Context context, Listener listener) {
        vehicle = new Vehicle(context.getApplicationContext());
        this.listener = listener;
    }

    public void connect() {
        startedAt = android.os.SystemClock.elapsedRealtime();
        try {
            vehicle.initialize();
            window = vehicle.getWindow();
            checkSupport();
        } catch (Exception exception) {
            listener.onError("Pleos Vehicle 서비스에 연결할 수 없습니다.");
        }
    }

    private void checkSupport() {
        if (closed) return;
        try {
            window.checkWindowPositionCapabilities((config, bounds) -> {
                if (closed || ready) return Unit.INSTANCE;
                AccessType access = config.getAccessType();
                AreaBounds<Integer> supported = bounds.get(area);
                if ((access != AccessType.READ && access != AccessType.READ_WRITE) || supported == null) {
                    main.post(() -> listener.onError("이 차에서는 운전석 창 위치 읽기를 지원하지 않습니다."));
                    return Unit.INSTANCE;
                }
                int minimum = supported.getMinValue();
                int maximum = supported.getMaxValue();
                if (maximum <= minimum) {
                    main.post(() -> listener.onError("차량이 유효한 창 위치 범위를 제공하지 않았습니다."));
                    return Unit.INSTANCE;
                }
                this.minimum = minimum;
                this.maximum = maximum;
                ready = true;
                updates = new WindowPositionListener() {
                    @Override public void onWindowPositionUpdated(Integer value) {
                        if (closed) return;
                        main.post(() -> {
                            if (closed) return;
                            if (value == null || value < minimum || value > maximum) listener.onError("창 위치 데이터가 없거나 범위를 벗어났습니다.");
                            else listener.onPosition(value);
                        });
                    }
                    @Override public void onFailed(Exception exception) {
                        main.post(() -> { if (!closed) listener.onError("창 위치 구독이 중단됐습니다. 이번 관찰은 불완전합니다."); });
                    }
                };
                main.post(() -> {
                    if (closed) return;
                    listener.onReady(minimum, maximum);
                    try {
                        window.registerWindowPosition(area, updates);
                        read();
                    } catch (Exception exception) {
                        listener.onError("창 위치 구독 권한 또는 연결을 확인해 주세요.");
                    }
                });
                return Unit.INSTANCE;
            }, exception -> {
                main.post(() -> {
                    if (closed) return;
                    if (android.os.SystemClock.elapsedRealtime() - startedAt < 8000) main.postDelayed(this::checkSupport, 750);
                    else listener.onError("Pleos Vehicle 연결을 확인하지 못했습니다. 권한·서비스를 확인해 주세요.");
                });
                return Unit.INSTANCE;
            });
        } catch (Exception exception) {
            listener.onError("창 위치 지원 여부를 조회하지 못했습니다.");
        }
    }

    public void read() {
        read(null);
    }

    public void read(Runnable received) {
        if (!ready || closed) return;
        try {
            window.getCurrentWindowPosition(area, value -> {
                main.post(() -> {
                    if (closed) return;
                    if (value == null || value < minimum || value > maximum) listener.onError("현재 창 위치가 없거나 유효 범위를 벗어났습니다.");
                    else {
                        listener.onPosition(value);
                        if (received != null) received.run();
                    }
                });
                return Unit.INSTANCE;
            }, exception -> {
                main.post(() -> { if (!closed) listener.onError("현재 창 위치 조회가 실패했습니다."); });
                return Unit.INSTANCE;
            });
        } catch (Exception exception) {
            listener.onError("현재 창 위치를 읽을 연결이 없습니다.");
        }
    }

    public void close() {
        closed = true;
        main.removeCallbacksAndMessages(null);
        if (window != null && updates != null) {
            try { window.unregisterWindowPosition(area, updates); } catch (Exception ignored) { }
        }
        try { vehicle.release(); } catch (Exception ignored) { }
    }
}
