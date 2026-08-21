package app.zipper.knot.hooks;

import android.content.Context;
import android.content.res.Resources;
import app.zipper.knot.Knot;
import app.zipper.knot.LoadParam;
import app.zipper.knot.Reflect;
import app.zipper.knot.SettingsStore;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

final class MiniColorBridge26130 {

  interface Resolver {
    Integer resolve(String token);
  }

  private static final String MINI_ROOT_CLASS = "com.linecorp.line.mini.home.impl.list.b";
  private static final String MINI_STATE_CLASS = "com.linecorp.line.mini.home.impl.list.i";
  private static final String COMPOSER_CLASS = "h3.r";
  private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();
  private static final Set<String> UNMAPPED = ConcurrentHashMap.newKeySet();
  private static final ThreadLocal<Integer> MINI_DEPTH = ThreadLocal.withInitial(() -> 0);
  private static volatile boolean installed;

  private MiniColorBridge26130() {}

  static synchronized void install(
      BooleanSupplier active, Resolver resolver, LoadParam lpparam, String tag) throws Throwable {
    if (installed) return;

    Method colorResource =
        Reflect.findMethodExact("e12.a", lpparam.classLoader, "a", int.class);

    Knot.module
        .hook(colorResource)
        .intercept(
            chain -> {
              if (!active.getAsBoolean() || !isMiniActive()) return chain.proceed();

              int id = (Integer) chain.getArg(0);
              String token = resourceEntryName(id);
              if (token == null) return chain.proceed();

              Integer color;
              try {
                color = resolver.resolve(token);
              } catch (Throwable t) {
                Knot.log(tag + ": Mini semantic resolver failed for " + token + ": " + t);
                return chain.proceed();
              }

              if (color == null) {
                if (UNMAPPED.size() < 160 && UNMAPPED.add(token)) {
                  Knot.log(tag + ": unmapped Mini semantic " + token);
                }
                return chain.proceed();
              }

              if (LOGGED.add(token)) {
                Knot.log(
                    tag
                        + ": Mini semantic "
                        + token
                        + " -> "
                        + String.format(Locale.ROOT, "#%08X", color));
              }
              return toComposeColor(color);
            });

    installMiniCompositionScope(lpparam, tag);
    installed = true;
    Knot.log(tag + ": installed LINE 26.13.0 Mini Compose color bridge");
  }

  static boolean isMiniActive() {
    return MINI_DEPTH.get() > 0;
  }

  static void resetProbe() {
    LOGGED.clear();
    UNMAPPED.clear();
  }

  private static void installMiniCompositionScope(LoadParam lpparam, String tag) {
    try {
      Class<?> rootClass = Reflect.findClass(MINI_ROOT_CLASS, lpparam.classLoader);
      Method root = null;
      for (Method method : rootClass.getDeclaredMethods()) {
        if (!"a".equals(method.getName())) continue;
        Class<?>[] params = method.getParameterTypes();
        if (params.length != 13) continue;
        if (!MINI_STATE_CLASS.equals(params[0].getName())) continue;
        if (!COMPOSER_CLASS.equals(params[10].getName())) continue;
        if (params[11] != int.class || params[12] != int.class) continue;
        root = method;
        break;
      }
      if (root == null) {
        Knot.log(tag + ": Mini root composable not found");
        return;
      }
      root.setAccessible(true);
      Knot.module
          .hook(root)
          .intercept(
              chain -> {
                int previous = MINI_DEPTH.get();
                MINI_DEPTH.set(previous + 1);
                if (previous == 0) Knot.log(tag + ": Mini composition theme override active");
                try {
                  return chain.proceed();
                } finally {
                  if (previous == 0) MINI_DEPTH.remove();
                  else MINI_DEPTH.set(previous);
                }
              });
      Knot.log(tag + ": installed Mini root composable scope");
    } catch (Throwable t) {
      Knot.log(tag + ": Mini composable scope install failed: " + t);
    }
  }

  private static String resourceEntryName(int id) {
    if ((id >>> 24) != 0x7f) return null;

    Context context = SettingsStore.getContext();
    if (context == null) return null;
    Resources resources = context.getResources();
    try {
      if (!"jp.naver.line.android".equals(resources.getResourcePackageName(id))) return null;
      return resources.getResourceEntryName(id);
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static long toComposeColor(int color) {
    return (color & 0xFFFFFFFFL) << 32;
  }
}
