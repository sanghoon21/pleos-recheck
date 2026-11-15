package io.recheck.app;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Atomic, app-private case storage. Demo and vehicle observations use separate files. */
public final class CaseStore {
    private final AtomicFile file;
    private final String expectedSource;

    public CaseStore(Context context, boolean demo) {
        file = new AtomicFile(new File(context.getFilesDir(), demo ? "demo-case.json" : "vehicle-case.json"));
        expectedSource = demo ? "DEMO" : "PLEOS";
    }

    public List<Observation> load() throws Exception {
        List<Observation> observations = new ArrayList<>();
        if (!file.getBaseFile().exists()) return observations;
        JSONObject root = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
        if (root.getInt("schemaVersion") != 1) throw new IllegalStateException("Unsupported record schema");
        JSONArray array = root.getJSONArray("observations");
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.getJSONObject(i);
            Observation observation = new Observation();
            observation.id = object.getString("id");
            observation.source = object.getString("source");
            if (!expectedSource.equals(observation.source)) throw new IllegalStateException("Record source does not match storage");
            observation.stage = object.getString("stage");
            observation.conditions = object.getString("conditions");
            observation.capturedAt = object.getLong("capturedAt");
            observation.minimum = object.getInt("minimum");
            observation.maximum = object.getInt("maximum");
            observation.closedValue = object.getInt("closedValue");
            observation.userConfirmed = object.getBoolean("userConfirmed");
            observation.interrupted = object.getBoolean("interrupted");
            JSONArray points = object.getJSONArray("points");
            for (int j = 0; j < points.length(); j++) {
                JSONArray point = points.getJSONArray(j);
                observation.points.add(new Observation.Point(point.getLong(0), point.getInt(1)));
            }
            observations.add(observation);
        }
        return observations;
    }

    public void save(List<Observation> observations) throws Exception {
        JSONArray array = new JSONArray();
        for (Observation observation : observations) {
            if (!expectedSource.equals(observation.source)) throw new IllegalArgumentException("Record source does not match storage");
            JSONObject object = new JSONObject();
            object.put("id", observation.id);
            object.put("source", observation.source);
            object.put("stage", observation.stage);
            object.put("conditions", observation.conditions);
            object.put("capturedAt", observation.capturedAt);
            object.put("minimum", observation.minimum);
            object.put("maximum", observation.maximum);
            object.put("closedValue", observation.closedValue);
            object.put("userConfirmed", observation.userConfirmed);
            object.put("interrupted", observation.interrupted);
            JSONArray points = new JSONArray();
            for (Observation.Point point : observation.points) {
                points.put(new JSONArray().put(point.elapsedMs).put(point.rawPosition));
            }
            object.put("points", points);
            array.put(object);
        }
        JSONObject root = new JSONObject().put("schemaVersion", 1).put("observations", array);
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(root.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (Exception exception) {
            if (output != null) file.failWrite(output);
            throw exception;
        }
    }
}
