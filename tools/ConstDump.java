import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

public class ConstDump {
    public static void main(String[] args) throws Exception {
        dump("com.kuri.plugin.kuriclickconstruction.e.a");
        // MenuConfig impl: the injected menu option text
        Class<?> ab = Class.forName("com.kuri.plugin.kuriclickconstruction.a.b");
        Object inst = ab.getDeclaredConstructor().newInstance();
        Object menuText = ab.getMethod("getMenuOptionText").invoke(inst);
        System.out.println();
        System.out.println("MENU OPTION TEXT = " + menuText);
    }

    static void dump(String cls) throws Exception {
        Class<?> c = Class.forName(cls);
        System.out.println("=== " + cls + " static fields ===");
        for (Field f : c.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                continue;
            }
            f.setAccessible(true);
            Object v = f.get(null);
            String s = String.valueOf(v);
            if (s.length() > 300) {
                s = s.substring(0, 300) + "...";
            }
            System.out.println(f.getName() + " : " + f.getType().getSimpleName() + " = " + s);
        }
    }
}
