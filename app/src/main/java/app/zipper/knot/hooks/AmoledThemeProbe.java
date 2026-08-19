package app.zipper.knot.hooks;

import android.app.Activity;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import app.zipper.knot.Knot;
import app.zipper.knot.LineVersion;
import app.zipper.knot.LoadParam;
import app.zipper.knot.Reflect;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

final class AmoledThemeProbe {

  private static final String INPUT_PASS_ACTIVITY =
      "com.linecorp.line.passlock.InputPassActivity";
  private static final Set<String> logged = Collections.synchronizedSet(new HashSet<>());

  private AmoledThemeProbe() {}

  static void install(
      LineVersion.Config cfg, LoadParam lpparam, BooleanSupplier active, String tag) {
    installInputPassActivityProbe(lpparam, active, tag);
    installPasslockThemeManagerProbe(cfg, lpparam, active, tag);
    logOnce(tag + ": ThemeProbe installed for passcode screen");
  }

  private static void installInputPassActivityProbe(
      LoadParam lpparam, BooleanSupplier active, String tag) {
    try {
      Class<?> inputPass = Reflect.findClass(INPUT_PASS_ACTIVITY, lpparam.classLoader);
      hookActivityPhase(inputPass, "onCreate", new Class<?>[] {Bundle.class}, "onCreate", active, tag);
      hookActivityPhase(inputPass, "onStart", new Class<?>[0], "onStart", active, tag);
    } catch (Throwable t) {
      Knot.log(tag + ": ThemeProbe InputPassActivity unavailable: " + t);
    }
  }

  private static void hookActivityPhase(
      Class<?> activityClass,
      String methodName,
      Class<?>[] parameterTypes,
      String phase,
      BooleanSupplier active,
      String tag) {
    Method method = Reflect.findMethodExact(activityClass, methodName, parameterTypes);
    Knot.module
        .hook(method)
        .intercept(
            chain -> {
              Object result = chain.proceed();
              if (active.getAsBoolean()) {
                applyAndLogPasscodeState((Activity) chain.getThisObject(), phase, tag);
              }
              return result;
            });
  }

  private static void applyAndLogPasscodeState(Activity activity, String phase, String tag) {
    try {
      Resources res = activity.getResources();
      String pkg = activity.getPackageName();
      int rootId = res.getIdentifier("passcode_bg", "id", pkg);
      int primaryBackgroundId = res.getIdentifier("primaryBackground", "color", pkg);
      View root = rootId == 0 ? null : activity.findViewById(rootId);
      Drawable originalRootBackground = root == null ? null : root.getBackground();
      Integer routedPrimaryBackground =
          primaryBackgroundId == 0
              ? null
              : res.getColor(primaryBackgroundId, activity.getTheme());
      if (root != null && routedPrimaryBackground != null) {
        root.setBackgroundColor(routedPrimaryBackground);
      }
      Drawable appliedRootBackground = root == null ? null : root.getBackground();
      int uiNight = res.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;

      logOnce(
          tag
              + ": ThemeProbe passcode phase="
              + phase
              + " activity="
              + activity.getClass().getName()
              + " uiNight="
              + uiNight
              + " rootId="
              + hex(rootId)
              + " originalRootBackground="
              + describeDrawable(originalRootBackground)
              + " primaryBackgroundId="
              + hex(primaryBackgroundId)
              + " routedPrimaryBackground="
              + colorHex(routedPrimaryBackground)
              + " appliedRootBackground="
              + describeDrawable(appliedRootBackground));
    } catch (Throwable t) {
      Knot.log(tag + ": ThemeProbe passcode state failed: " + t);
    }
  }

  private static void installPasslockThemeManagerProbe(
      LineVersion.Config cfg, LoadParam lpparam, BooleanSupplier active, String tag) {
    String className = cfg.nightMode.darkThemeManagerClass;
    if (className == null || className.isEmpty()) return;

    try {
      Class<?> themeManager = Reflect.findClass(className, lpparam.classLoader);
      for (Method method : themeManager.getDeclaredMethods()) {
        Class<?>[] params = method.getParameterTypes();
        if (params.length == 2
            && View.class.isAssignableFrom(params[0])
            && params[1].isArray()
            && method.getReturnType() == boolean.class) {
          method.setAccessible(true);
          Knot.module
              .hook(method)
              .intercept(
                  chain -> {
                    Object result = chain.proceed();
                    if (!active.getAsBoolean()) return result;

                    View view = (View) chain.getArg(0);
                    if (isPasslockView(view)) {
                      logOnce(
                          tag
                              + ": ThemeProbe manager."
                              + method.getName()
                              + " view="
                              + view.getClass().getName()
                              + " result="
                              + result
                              + " background="
                              + describeDrawable(view.getBackground()));
                    }
                    return result;
                  });
        } else if (params.length == 1
            && Set.class.isAssignableFrom(params[0])
            && Drawable.class.isAssignableFrom(method.getReturnType())) {
          method.setAccessible(true);
          Knot.module
              .hook(method)
              .intercept(
                  chain -> {
                    Object result = chain.proceed();
                    if (!active.getAsBoolean()) return result;

                    String caller = passlockCaller();
                    if (caller != null) {
                      logOnce(
                          tag
                              + ": ThemeProbe manager."
                              + method.getName()
                              + " caller="
                              + caller
                              + " keys="
                              + chain.getArg(0)
                              + " result="
                              + describeDrawable(
                                  result instanceof Drawable ? (Drawable) result : null));
                    }
                    return result;
                  });
        }
      }
    } catch (Throwable t) {
      Knot.log(tag + ": ThemeProbe passlock theme manager unavailable: " + t);
    }
  }

  private static boolean isPasslockView(View view) {
    if (view == null) return false;
    if (view.getClass().getName().startsWith("com.linecorp.line.passlock.")) return true;
    return view.getContext() != null
        && INPUT_PASS_ACTIVITY.equals(view.getContext().getClass().getName());
  }

  private static String passlockCaller() {
    for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
      if (frame.getClassName().startsWith("com.linecorp.line.passlock.")) {
        return frame.getClassName() + "#" + frame.getMethodName();
      }
    }
    return null;
  }

  private static String describeDrawable(Drawable drawable) {
    if (drawable == null) return "null";
    if (drawable instanceof ColorDrawable) {
      return drawable.getClass().getName()
          + "("
          + colorHex(((ColorDrawable) drawable).getColor())
          + ")";
    }
    return drawable.getClass().getName();
  }

  private static String colorHex(Integer color) {
    return color == null ? "null" : String.format("#%08X", color);
  }

  private static String hex(int value) {
    return value == 0 ? "0" : String.format("0x%08X", value);
  }

  private static void logOnce(String message) {
    if (logged.add(message)) Knot.log(message);
  }
}
