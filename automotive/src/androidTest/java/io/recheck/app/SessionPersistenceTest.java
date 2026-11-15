package io.recheck.app;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SessionPersistenceTest {
    private Observation sample(String source, String stage, int... values) {
        Observation observation = new Observation();
        observation.id = "test-" + stage;
        observation.source = source;
        observation.stage = stage;
        observation.conditions = "주차 후 첫 조작";
        observation.capturedAt = 1_800_000_000_000L;
        observation.minimum = 0;
        observation.maximum = 100;
        observation.closedValue = 0;
        observation.userConfirmed = true;
        for (int i = 0; i < values.length; i++) observation.points.add(new Observation.Point(i * 500L, values[i]));
        return observation;
    }

    @Test public void savesThreeObservationsAndKeepsVehicleSourceSeparate() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CaseStore demo = new CaseStore(context, true);
        CaseStore vehicle = new CaseStore(context, false);
        List<Observation> originalDemo = demo.load();
        List<Observation> originalVehicle = vehicle.load();
        try {
            List<Observation> sequence = new ArrayList<>();
            sequence.add(sample("DEMO", "정비 전", 80, 50, 10, 40));
            sequence.add(sample("DEMO", "인수할 때", 80, 50, 10, 0));
            sequence.add(sample("DEMO", "일상 재확인", 80, 50, 10, 40));
            demo.save(sequence);
            List<Observation> reopened = new CaseStore(context, true).load();
            assertEquals(3, reopened.size());
            assertEquals("올라간 뒤 되돌아옴", reopened.get(0).result());
            assertEquals("닫힘 위치 관측", reopened.get(1).result());
            assertEquals("올라간 뒤 되돌아옴", reopened.get(2).result());
            assertTrue(reopened.get(0).comparableTo(reopened.get(2)));
            assertEquals(1500, reopened.get(2).points.get(3).elapsedMs);
            assertEquals(originalVehicle.size(), new CaseStore(context, false).load().size());
            List<Observation> real = new ArrayList<>();
            real.add(sample("PLEOS", "정비 전", 80, 40, 0));
            vehicle.save(real);
            assertEquals(3, new CaseStore(context, true).load().size());
            assertFalse(reopened.get(0).comparableTo(new CaseStore(context, false).load().get(0)));
            try {
                demo.save(real);
                fail("A vehicle source must never be saved in demo storage");
            } catch (IllegalArgumentException expected) { }
            assertEquals(3, new CaseStore(context, true).load().size());
        } finally {
            demo.save(originalDemo);
            vehicle.save(originalVehicle);
        }
    }
}
