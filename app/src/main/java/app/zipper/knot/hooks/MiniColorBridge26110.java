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

final class MiniColorBridge26110 {

  interface Resolver {
    Integer resolve(String token);
  }

  private static final String MINI_ACTIVITY =
      "com.linecorp.line.mini.home.impl.list.MiniAppHomeListActivity";
  private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();
  private static final Set<String> UNMAPPED = ConcurrentHashMap.newKeySet();
  private static volatile boolean installed;
  private static volatile boolean miniActive;

  private MiniColorBridge26110() {}

  static synchronized void install(
      BooleanSupplier active, Resolver resolver, LoadParam lpparam, String tag) throws Throwable {
    if (installed) return;

    Method colorResource =
        Reflect.findMethodExact("zy1.a", lpparam.classLoader, "a", int.class);

    Knot.module
        .hook(colorResource)
        .intercept(
            chain -> {
              if (!active.getAsBoolean()) return chain.proceed();

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

    installMiniLifecycle(lpparam, tag);
    installed = true;
    Knot.log(tag + ": installed LINE 26.11.0 Mini Compose color bridge");
  }

  static boolean isMiniActive() {
    return miniActive;
  }

  static void resetProbe() {
    LOGGED.clear();
    UNMAPPED.clear();
  }

  private static void installMiniLifecycle(LoadParam lpparam, String tag) {
    try {
      Method onResume = Reflect.findMethodExact(MINI_ACTIVITY, lpparam.classLoader, "onResume");
      Knot.module
          .hook(onResume)
          .intercept(
              chain -> {
                Object result = chain.proceed();
                miniActive = true;
                Knot.log(tag + ": Mini theme override active");
                return result;
              });

      Method onPause = Reflect.findMethodExact(MINI_ACTIVITY, lpparam.classLoader, "onPause");
      Knot.module
          .hook(onPause)
          .intercept(
              chain -> {
                miniActive = false;
                return chain.proceed();
              });
    } catch (Throwable t) {
      Knot.log(tag + ": Mini lifecycle probe install failed: " + t);
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
