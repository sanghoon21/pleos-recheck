package io.recheck.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class ObservationTest {
    private Observation sample(int... values) {
        Observation observation = new Observation();
        observation.source = "DEMO";
        observation.conditions = "주차 후 첫 조작";
        observation.minimum = 0;
        observation.maximum = 100;
        observation.closedValue = 0;
        observation.userConfirmed = true;
        for (int i = 0; i < values.length; i++) observation.points.add(new Observation.Point(i * 500L, values[i]));
        return observation;
    }

    @Test public void distinguishesReversalClosureAndMissingObservation() {
        assertEquals("올라간 뒤 되돌아옴", sample(80, 60, 30, 10, 25, 40).result());
        assertEquals("닫힘 위치 관측", sample(80, 60, 30, 10, 0).result());
        assertEquals("관찰 불충분", sample(80).result());
        Observation interrupted = sample(80, 40, 0);
        interrupted.interrupted = true;
        assertEquals("관찰 불충분", interrupted.result());
    }

    @Test public void doesNotCompareDifferentSourcesOrStartingConditions() {
        Observation before = sample(80, 30, 50);
        assertTrue(before.comparableTo(sample(80, 40, 0)));
        Observation vehicle = sample(80, 40, 0);
        vehicle.source = "PLEOS";
        assertFalse(before.comparableTo(vehicle));
        assertFalse(before.comparableTo(sample(30, 20, 0)));
        Observation differentCondition = sample(80, 40, 0);
        differentCondition.conditions = "연속 조작";
        assertFalse(before.comparableTo(differentCondition));
    }

    @Test public void rejectsInvalidRangeValuesAndEventOrder() {
        Observation baseline = sample(80, 30, 50);
        Observation invalidRange = sample(80, 40, 0);
        invalidRange.maximum = 0;
        assertFalse(baseline.comparableTo(invalidRange));
        assertEquals("관찰 불충분", invalidRange.result());
        Observation outside = sample(80, 120, 0);
        assertFalse(baseline.comparableTo(outside));
        assertEquals("관찰 불충분", outside.result());
        Observation duplicate = sample(80, 40, 0);
        duplicate.points.set(2, new Observation.Point(500, 0));
        assertFalse(baseline.comparableTo(duplicate));
        Observation reversed = sample(80, 40, 0);
        reversed.points.set(2, new Observation.Point(100, 0));
        assertEquals("관찰 불충분", reversed.result());
    }
}
