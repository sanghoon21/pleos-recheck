package io.recheck.app;

import java.util.ArrayList;
import java.util.List;

/** A received sensor event, timestamped at the app rather than at the vehicle sensor. */
public final class Observation {
    public static final class Point {
        public final long elapsedMs;
        public final int rawPosition;

        public Point(long elapsedMs, int rawPosition) {
            this.elapsedMs = elapsedMs;
            this.rawPosition = rawPosition;
        }
    }

    public String id;
    public String source;
    public String stage;
    public String conditions;
    public long capturedAt;
    public int minimum;
    public int maximum;
    public int closedValue;
    public boolean userConfirmed;
    public boolean interrupted;
    public final List<Point> points = new ArrayList<>();

    public boolean hasValidObservation() {
        if (interrupted || !userConfirmed || points.size() < 3 || maximum <= minimum
                || closedValue < minimum || closedValue > maximum) return false;
        long previous = -1;
        for (Point point : points) {
            if (point.elapsedMs < 0 || point.elapsedMs <= previous
                    || point.rawPosition < minimum || point.rawPosition > maximum) return false;
            previous = point.elapsedMs;
        }
        return true;
    }

    public String result() {
        if (!hasValidObservation()) {
            return "관찰 불충분";
        }
        int tolerance = Math.max(1, (maximum - minimum) / 50);
        int initialDistance = Math.abs(points.get(0).rawPosition - closedValue);
        int closestDistance = initialDistance;
        boolean movedTowardsClosed = false;
        boolean movedAwayAgain = false;
        for (Point point : points) {
            int distance = Math.abs(point.rawPosition - closedValue);
            if (distance + tolerance < closestDistance) {
                closestDistance = distance;
                movedTowardsClosed = true;
            }
            if (movedTowardsClosed && distance > closestDistance + tolerance * 2) {
                movedAwayAgain = true;
            }
        }
        int finalDistance = Math.abs(points.get(points.size() - 1).rawPosition - closedValue);
        if (movedTowardsClosed && movedAwayAgain) return "올라간 뒤 되돌아옴";
        if (movedTowardsClosed && finalDistance <= tolerance) return "닫힘 위치 관측";
        return "닫힘 확인 전";
    }

    public boolean comparableTo(Observation other) {
        if (other == null || !hasValidObservation() || !other.hasValidObservation()) return false;
        if (source == null || !source.equals(other.source) || conditions == null) return false;
        if (!conditions.equals(other.conditions) || minimum != other.minimum || maximum != other.maximum) return false;
        if (closedValue != other.closedValue) return false;
        int tolerance = Math.max(1, (maximum - minimum) / 10);
        return Math.abs(points.get(0).rawPosition - other.points.get(0).rawPosition) <= tolerance;
    }

    public float height(Point point) {
        float range = Math.max(1, maximum - minimum);
        return 1f - Math.min(1f, Math.abs(point.rawPosition - closedValue) / range);
    }
}
