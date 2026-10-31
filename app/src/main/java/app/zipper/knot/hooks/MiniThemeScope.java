package app.zipper.knot.hooks;

import app.zipper.knot.Knot;
import app.zipper.knot.LineVersion;
import app.zipper.knot.LoadParam;
import app.zipper.knot.Reflect;
import java.lang.reflect.Method;

/** Tracks Mini's Compose rendering scope, without assuming an R8 method name. */
final class MiniThemeScope {
  private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
  private static volatile boolean installed;

  private MiniThemeScope() {}

  static boolean isMiniActive() {
    return DEPTH.get() > 0;
  }

  static void resetProbe() {}

  static synchronized void install(LineVersion.Config.Theme mapping, LoadParam lpparam, String tag) {
    if (installed || mapping.miniRootClass.isEmpty() || mapping.methodMiniRoot.isEmpty()) return;
    try {
      Class<?> root = Reflect.findClass(mapping.miniRootClass, lpparam.classLoader);
      Method target = null;
      for (Method method : root.getDeclaredMethods()) {
        if (!method.getName().equals(mapping.methodMiniRoot)) continue;
        if (method.getParameterCount() != 13) continue;
        Class<?>[] params = method.getParameterTypes();
        if (params[11] != int.class || params[12] != int.class) continue;
        target = method;
        break;
      }
      if (target == null) return;
      target.setAccessible(true);
      Knot.module.hook(target).intercept(chain -> {
        int depth = DEPTH.get();
        DEPTH.set(depth + 1);
        try {
          return chain.proceed();
        } finally {
          if (depth == 0) DEPTH.remove();
          else DEPTH.set(depth);
        }
      });
      installed = true;
      Knot.log(tag + ": Mini composition scope detected");
    } catch (Throwable t) {
      Knot.log(tag + ": Mini scope unavailable: " + t);
    }
  }
}
