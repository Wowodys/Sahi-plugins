package kuri;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Runtime dump sink for the capture agent. Must stay dependency-free:
 * everything reflective, JDK-only, never throws.
 */
public final class Helper {
    static final String CAP_DIR = System.getProperty(
        "kuri.capture.dir",
        "C:\\Users\\sahij\\.zcode\\workspace\\draft-2cdfe928-147a-4da2-9b75-c1084b9f7576\\kuri-analysis\\capture");
    static final String HOST_SUFFIX = System.getProperty("kuri.capture.host", "kuriplugins.com");
    static final long MAX_BODY = 64L * 1024L * 1024L;
    static final Object LOCK = new Object();
    static boolean initialized;

    private Helper() {}

    // ---------- called from instrumented okhttp3.RealCall ----------
    public static void onOkhttpResponse(Object response) {
        try {
            Object req = response.getClass().getMethod("request").invoke(response);
            Object url = req.getClass().getMethod("url").invoke(req);
            String host = (String) url.getClass().getMethod("host").invoke(url);
            if (HOST_SUFFIX.isEmpty() || host.endsWith(HOST_SUFFIX)) {
                String path = (String) url.getClass().getMethod("encodedPath").invoke(url);
                String method = (String) req.getClass().getMethod("method").invoke(req);
                int code = (Integer) response.getClass().getMethod("code").invoke(response);
                String body = null;
                try {
                    Object peeked = response.getClass().getMethod("peekBody", long.class)
                        .invoke(response, MAX_BODY);
                    body = (String) peeked.getClass().getMethod("string").invoke(peeked);
                } catch (Throwable ignore) {
                    body = "<peek failed: " + ignore + ">";
                }
                record("okhttp",
                    "proc", proc(),
                    "method", method,
                    "url", "https://" + host + path,
                    "status", String.valueOf(code),
                    "bodyLen", body == null ? "0" : String.valueOf(body.length()),
                    "body", body);
            }
        } catch (Throwable t) {
            error("okhttp-response", t);
        }
    }

    // ---------- called from instrumented KencFile ----------
    public static void onKencArgs(Object[] args) {
        try {
            for (int i = 1; i < args.length; i++) {
                Object a = args[i];
                if (a == null) {
                    record("kenc", "proc", proc(), "argIndex", String.valueOf(i), "value", "null");
                } else {
                    record("kenc", "proc", proc(),
                        "argIndex", String.valueOf(i),
                        "class", a.getClass().getName(),
                        "toString", String.valueOf(a),
                        "fields", dumpFields(a));
                }
            }
        } catch (Throwable t) {
            error("kenc-args", t);
        }
    }

    public static void onKencResult(Object identity, byte[] plaintext) {
        try {
            if (plaintext == null) {
                return;
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(plaintext);
            String sha = hex(d);
            Path dir = Path.of(CAP_DIR, "plaintexts");
            synchronized (LOCK) {
                Files.createDirectories(dir);
                Path out = dir.resolve(sha + ".jar");
                if (!Files.exists(out)) {
                    Files.write(out, plaintext);
                }
            }
            StringBuilder idStr = new StringBuilder();
            if (identity != null) {
                for (Field f : identity.getClass().getDeclaredFields()) {
                    f.setAccessible(true);
                    Object v = f.get(identity);
                    idStr.append(f.getName()).append("=")
                        .append(v instanceof byte[] ? hex((byte[]) v) : String.valueOf(v))
                        .append(";");
                }
            }
            record("kenc-plaintext", "proc", proc(),
                "sha256", sha, "size", String.valueOf(plaintext.length),
                "saved", "plaintexts/" + sha + ".jar",
                "identity", idStr.toString());
        } catch (Throwable t) {
            error("kenc-plaintext", t);
        }
    }

    // ---------- plumbing ----------
    static String proc() {
        try {
            String cp = System.getProperty("java.class.path", "");
            String cmd = System.getProperty("sun.java.command", "");
            String all = cp + " " + cmd;
            if (all.contains("kuri-runelite") || all.contains("injected-client")
                || all.contains("--classpath")) {
                return "client";
            }
            return "launcher";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    static String dumpFields(Object o) throws Exception {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                f.setAccessible(true);
                Object v = f.get(o);
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(json(f.getName())).append("\":");
                if (v == null) {
                    sb.append("null");
                } else if (v instanceof byte[]) {
                    byte[] b = (byte[]) v;
                    sb.append("{\"hex\":\"").append(hex(b))
                        .append("\",\"b64\":\"").append(Base64.getEncoder().encodeToString(b))
                        .append("\",\"len\":").append(b.length).append('}');
                } else {
                    sb.append('"').append(json(String.valueOf(v))).append('"');
                }
            }
        }
        return sb.append('}').toString();
    }

    static void record(String kind, String... kv) {
        try {
            StringBuilder sb = new StringBuilder(256);
            sb.append("{\"ts\":\"").append(java.time.Instant.now().toString())
                .append("\",\"pid\":").append(ProcessHandle.current().pid())
                .append(",\"kind\":\"").append(json(kind)).append('"');
            for (int i = 0; i + 1 < kv.length; i += 2) {
                sb.append(",\"").append(json(kv[i])).append("\":");
                sb.append('"').append(json(kv[i + 1])).append('"');
            }
            sb.append("}\n");
            Path dir = Path.of(CAP_DIR);
            Path out = dir.resolve("capture.jsonl");
            synchronized (LOCK) {
                Files.createDirectories(dir);
                Files.write(out, sb.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Throwable t) {
            error("record", t);
        }
    }

    static void error(String where, Throwable t) {
        try {
            record("error", "where", where, "what", String.valueOf(t));
        } catch (Throwable ignore) {
            // last resort: stderr
            System.err.println("[kuri-capture] " + where + ": " + t);
        }
    }

    static String hex(byte[] b) {
        char[] B64 = "0123456789abcdef".toCharArray();
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 0xFF;
            out[i * 2] = B64[v >>> 4];
            out[i * 2 + 1] = B64[v & 0xF];
        }
        return new String(out);
    }

    static String json(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
