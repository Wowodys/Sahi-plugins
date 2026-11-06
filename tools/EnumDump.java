import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class EnumDump {
    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName("com.kuri.plugin.kuriclickconstruction.a.c");
        Object[] vals = c.getEnumConstants();
        System.out.println("a.c enum (" + vals.length + " values):");
        for (Object v : vals) {
            StringBuilder sb = new StringBuilder("  " + ((Enum<?>) v).name() + " [");
            for (Method m : c.getMethods()) {
                if (m.getParameterCount() == 0 && !m.getDeclaringClass().equals(Object.class)
                    && (m.getReturnType() == String.class || m.getReturnType() == int.class)) {
                    try {
                        sb.append(m.getName()).append("=").append(m.invoke(v)).append(" ");
                    } catch (Exception ignore) {}
                }
            }
            sb.append("]");
            System.out.println(sb);
        }
    }
}
