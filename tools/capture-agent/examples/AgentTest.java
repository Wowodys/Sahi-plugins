import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class AgentTest {
    public static void main(String[] args) throws Exception {
        String captureDir = System.getProperty("kuri.capture.dir");

        // 1. okhttp hook: sync GET against local test server
        OkHttpClient client = new OkHttpClient();
        Request req = new Request.Builder().url("http://127.0.0.1:8977/plan.json").build();
        try (Response resp = client.newCall(req).execute()) {
            System.out.println("HTTP status: " + resp.code() + ", body head: "
                + resp.peekBody(40).string().replace("\n", ""));
        }

        // 2. KencFile hook: invoke candidate static method with real container bytes
        byte[] container = Files.readAllBytes(Path.of(
            "C:\\Users\\sahij\\AppData\\Local\\Kuri\\cache\\plugins",
            "a09cba5ae9daa86a8512b127011d638603be5a1f9ad53feaba4a674787ad6fc1"));
        Class<?> kenc = Class.forName("k.n52d6dca4ff92");
        Method target = null;
        for (Method m : kenc.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                && m.getReturnType() == byte[].class && p.length == 3
                && p[0] == java.io.InputStream.class) {
                target = m;
                break;
            }
        }
        System.out.println("KencFile method: " + target);
        target.setAccessible(true);
        try {
            target.invoke(null, new ByteArrayInputStream(container), null, null);
            System.out.println("UNEXPECTED: invocation returned");
        } catch (java.lang.reflect.InvocationTargetException e) {
            System.out.println("Invocation threw as expected: "
                + e.getCause().getClass().getName());
        }

        // 3. plaintext dump path
        kuri.Helper.onKencResult(new Object(), "fake plugin jar bytes".getBytes());
        System.out.println("capture dir contents:");
        Files.walk(Path.of(captureDir)).forEach(p -> System.out.println("   " + p));
    }
}
