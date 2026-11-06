import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

public class AgentTest2 {
    public static void main(String[] args) throws Exception {
        byte[] container = Files.readAllBytes(Path.of(
            "C:/Users/sahij/AppData/Local/Kuri/cache/plugins",
            "a09cba5ae9daa86a8512b127011d638603be5a1f9ad53feaba4a674787ad6fc1"));
        Class<?> kenc = Class.forName("k.n52d6dca4ff92");
        // invoke the WRAPPER by its exact original name
        Method m = kenc.getDeclaredMethod("nc556d7d7a991", java.io.InputStream.class,
            Class.forName("k.n874f7bc9a85c"), Class.forName("k.nce2b3b005977"));
        m.setAccessible(true);
        try {
            m.invoke(null, new ByteArrayInputStream(container), null, null);
            System.out.println("UNEXPECTED return");
        } catch (Exception e) {
            System.out.println("threw as expected: " + e.getCause().getClass().getName());
        }
        System.out.println("done");
    }
}
