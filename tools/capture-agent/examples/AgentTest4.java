import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

public class AgentTest4 {
    public static void main(String[] args) throws Exception {
        // container + params produced by make_test_container.py
        byte[] container = Files.readAllBytes(Path.of("test-container.kenc"));
        byte[] key = Base64.getDecoder().decode(args[0]);
        byte[] nonce = Base64.getDecoder().decode(args[1]);
        byte[] sha = hexToBytes(args[2]);
        long size = container.length - 52;

        Class<?> ident = Class.forName("k.n874f7bc9a85c");
        Class<?> kmC = Class.forName("k.nce2b3b005977");
        Object identity = ident.getConstructor(String.class, String.class, byte[].class, long.class)
            .newInstance("test-plugin", "1.0.0", sha, (long) size);
        Object keyMaterial = kmC.getConstructor(byte[].class, byte[].class)
            .newInstance(key, nonce);

        Class<?> kenc = Class.forName("k.n52d6dca4ff92");
        Method m = kenc.getDeclaredMethod("nc556d7d7a991",
            java.io.InputStream.class, ident, kmC);
        m.setAccessible(true);
        byte[] pt = (byte[]) m.invoke(null, new ByteArrayInputStream(container), identity, keyMaterial);
        System.out.println("DECRYPTED " + pt.length + " bytes; head: "
            + new String(pt, 0, Math.min(20, pt.length)));
    }

    static byte[] hexToBytes(String h) {
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
