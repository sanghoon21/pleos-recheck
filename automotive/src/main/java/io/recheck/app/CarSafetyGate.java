package io.recheck.app;

import android.car.Car;
import android.car.VehicleGear;
import android.car.VehiclePropertyIds;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** Fail-closed parked-only gate. Rechecks current values every second, and on observation start. */
public final class CarSafetyGate {
    public interface Listener { void onState(boolean allowed, String reason); }
    private final Listener listener;
    private Car car;
    private CarPropertyManager properties;
    private CarUxRestrictionsManager restrictions;
    private boolean parked;
    private boolean stopped;
    private boolean uxAllowed;
    private boolean closed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            refreshNow();
            if (!closed) main.postDelayed(this, 1000);
        }
    };

    private final CarPropertyManager.CarPropertyEventCallback callback = new CarPropertyManager.CarPropertyEventCallback() {
        @Override public void onChangeEvent(CarPropertyValue value) {
            if (closed) return;
            if (value.getStatus() != CarPropertyValue.STATUS_AVAILABLE) {
                parked = false;
                stopped = false;
            } else if (value.getPropertyId() == VehiclePropertyIds.GEAR_SELECTION) {
                parked = ((Integer) value.getValue()) == VehicleGear.GEAR_PARK;
            } else if (value.getPropertyId() == VehiclePropertyIds.PERF_VEHICLE_SPEED) {
                stopped = freshSpeed(value) && Math.abs((Float) value.getValue()) < 0.01f;
            }
            publish();
        }
        @Override public void onErrorEvent(int propertyId, int areaId) {
            parked = false;
            stopped = false;
            publish();
        }
    };

    public CarSafetyGate(Context context, Listener listener) {
        this.listener = listener;
        car = Car.createCar(context, new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                if (closed) return;
                try {
                    properties = (CarPropertyManager) car.getCarManager(Car.PROPERTY_SERVICE);
                    restrictions = (CarUxRestrictionsManager) car.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE);
                    properties.registerCallback(callback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE);
                    properties.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL);
                    restrictions.registerListener(CarSafetyGate.this::updateRestrictions);
                    main.post(refresh);
                } catch (Exception exception) {
                    listener.onState(false, "주차·속도·주행 제한 신호를 확인할 수 없습니다.");
                }
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                parked = false;
                stopped = false;
                uxAllowed = false;
                publish();
            }
        });
        if (car == null) listener.onState(false, "차량 주행 제한 서비스가 없습니다.");
        else car.connect();
    }

    private boolean freshSpeed(CarPropertyValue value) {
        long age = SystemClock.elapsedRealtimeNanos() - value.getTimestamp();
        return age >= -2_000_000_000L && age <= 5_000_000_000L;
    }

    public boolean refreshNow() {
        if (closed || properties == null || restrictions == null) return false;
        try {
            CarPropertyValue<Integer> gear = properties.getProperty(Integer.class, VehiclePropertyIds.GEAR_SELECTION, 0);
            // Gear is ON_CHANGE: an old unchanged gear value is not by itself a failure.
            parked = gear != null && gear.getStatus() == CarPropertyValue.STATUS_AVAILABLE && gear.getValue() == VehicleGear.GEAR_PARK;
            CarPropertyValue<Float> speed = properties.getProperty(Float.class, VehiclePropertyIds.PERF_VEHICLE_SPEED, 0);
            stopped = speed != null && speed.getStatus() == CarPropertyValue.STATUS_AVAILABLE
                    && freshSpeed(speed) && Math.abs(speed.getValue()) < 0.01f;
            CarUxRestrictions state = restrictions.getCurrentCarUxRestrictions();
            uxAllowed = state != null && !state.isRequiresDistractionOptimization();
        } catch (Exception exception) {
            parked = false;
            stopped = false;
            uxAllowed = false;
        }
        publish();
        return parked && stopped && uxAllowed;
    }

    private void updateRestrictions(CarUxRestrictions state) {
        uxAllowed = state != null && !state.isRequiresDistractionOptimization();
        publish();
    }

    private void publish() {
        if (!closed) listener.onState(parked && stopped && uxAllowed,
                parked && stopped && uxAllowed ? "P · 정지 · 주행 제한 해제 확인" : "P·정지와 주행 제한 해제 확인을 기다립니다.");
    }

    public void close() {
        closed = true;
        main.removeCallbacksAndMessages(null);
        try { if (properties != null) properties.unregisterCallback(callback); } catch (Exception ignored) { }
        try { if (restrictions != null) restrictions.unregisterListener(); } catch (Exception ignored) { }
        try { if (car != null) car.disconnect(); } catch (Exception ignored) { }
    }
}
