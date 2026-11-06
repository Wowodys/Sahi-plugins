import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public class AgentTest3 {
    public static void main(String[] args) throws Exception {
        Class<?> kenc = Class.forName("k.n52d6dca4ff92");
        for (Method m : kenc.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && m.getReturnType() == byte[].class) {
                System.out.println("METHOD: " + m.getName() + " " + m);
            }
        }
    }
}
