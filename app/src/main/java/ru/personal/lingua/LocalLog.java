package ru.personal.lingua;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/** Deliberately excludes request/response bodies, recognition text, cookies and tokens. */
final class LocalLog {
    private static final String FILE = "debug.log";
    static synchronized void event(Context context, String eventCode) {
        // Only hard-coded event codes from call sites. Never add exception messages or user text.
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date()) + " | " + eventCode + "\n";
        try {
            if (context.getFileStreamPath(FILE).length() > 131072) {
                // Rotate by dropping old test events; no unbounded log growth.
                try (FileOutputStream reset = context.openFileOutput(FILE, Context.MODE_PRIVATE)) {
                    reset.write("log_rotated\n".getBytes(StandardCharsets.UTF_8));
                }
            }
            try (FileOutputStream out = context.openFileOutput(FILE, Context.MODE_APPEND)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) { }
    }
    static synchronized String read(Context context) {
        try (InputStream in = context.openFileInput(FILE)) {
            byte[] raw = new byte[Math.min(262144, Math.max(0, in.available()))];
            int used = in.read(raw);
            if (used < 0) return "Лог пока пуст.";
            String all = new String(raw, 0, used, StandardCharsets.UTF_8);
            return all.length() > 12000 ? all.substring(all.length() - 12000) : all;
        } catch (IOException ignored) { return "Лог пока пуст."; }
    }
}
