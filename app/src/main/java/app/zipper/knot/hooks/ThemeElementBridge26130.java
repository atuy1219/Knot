package app.zipper.knot.hooks;

import android.content.res.ColorStateList;
import app.zipper.knot.Knot;
import app.zipper.knot.LoadParam;
import app.zipper.knot.Reflect;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

final class ThemeElementBridge26130 {

  interface Resolver {
    Integer resolve(String component, String element);
  }

  private enum ColorSlot {
    BACKGROUND,
    BACKGROUND_IMAGE_TINT,
    IMAGE_TINT,
    TEXT,
    SHADOW,
    HINT
  }

  private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();
  private static final Set<String> UNMAPPED = ConcurrentHashMap.newKeySet();
  private static final Set<String> MINI_OVERRIDDEN = ConcurrentHashMap.newKeySet();
  private static volatile boolean installed;

  private ThemeElementBridge26130() {}

  static synchronized void install(
      BooleanSupplier active, Resolver resolver, LoadParam lpparam, String tag) throws Throwable {
    if (installed) return;

    ClassLoader cl = lpparam.classLoader;
    Class<?> keyClass = Reflect.findClass("m76.g", cl);
    Class<?> valueClass = Reflect.findClass("m76.j", cl);
    Class<?> colorClass = Reflect.findClass("m76.d", cl);
    Class<?> imageClass = Reflect.findClass("m76.f", cl);

    Constructor<?> colorConstructor =
        Reflect.findConstructorExact(colorClass, ColorStateList.class);
    Constructor<?> valueConstructor =
        Reflect.findConstructorExact(
            valueClass,
            imageClass,
            colorClass,
            colorClass,
            imageClass,
            colorClass,
            colorClass,
            colorClass,
            colorClass);
    Method themeResolver =
        Reflect.findMethodExact("o76.d", cl, "b", Map.class, Set.class);

    Knot.module
        .hook(themeResolver)
        .intercept(
            chain -> {
              if (!active.getAsBoolean()) return chain.proceed();

              Object mapArg = chain.getArg(0);
              Object setArg = chain.getArg(1);
              if (!(mapArg instanceof Map) || !(setArg instanceof Set)) {
                return chain.proceed();
              }

              @SuppressWarnings("rawtypes")
              Map source = (Map) mapArg;
              @SuppressWarnings("rawtypes")
              Set requested = (Set) setArg;
              @SuppressWarnings("rawtypes")
              Map extended = null;
              boolean miniOverride = MiniColorBridge26130.isMiniActive();

              for (Object key : requested) {
                if (key == null || !keyClass.isInstance(key)) continue;
                boolean exists = source.containsKey(key);
                if (exists && !miniOverride) continue;

                try {
                  String component = (String) Reflect.getObjectField(key, "a");
                  String element = (String) Reflect.getObjectField(key, "b");
                  Integer color = resolver.resolve(component, element);
                  ColorSlot slot = resolveSlot(element);
                  String identity = identity(component, element);

                  if (color == null || slot == null) {
                    if (!exists && UNMAPPED.size() < 160 && UNMAPPED.add(identity)) {
                      Knot.log(tag + ": unmapped theme element " + identity);
                    }
                    continue;
                  }

                  Object themeValue;
                  if (miniOverride && exists) {
                    themeValue =
                        overrideThemeValue(
                            source.get(key),
                            valueClass,
                            colorConstructor,
                            valueConstructor,
                            color,
                            slot);
                  } else {
                    themeValue = createThemeValue(colorConstructor, valueConstructor, color, slot);
                  }

                  if (extended == null) extended = new LinkedHashMap(source);
                  extended.put(key, themeValue);

                  if (miniOverride) {
                    if (MINI_OVERRIDDEN.add(identity)) {
                      Knot.log(
                          tag
                              + ": Mini theme element override "
                              + identity
                              + " ["
                              + slot.name().toLowerCase(Locale.ROOT)
                              + "] -> "
                              + String.format(Locale.ROOT, "#%08X", color));
                    }
                  } else if (LOGGED.add(identity)) {
                    Knot.log(
                        tag
                            + ": theme element "
                            + identity
                            + " ["
                            + slot.name().toLowerCase(Locale.ROOT)
                            + "] -> "
                            + String.format(Locale.ROOT, "#%08X", color));
                  }
                } catch (Throwable t) {
                  Knot.log(tag + ": theme element bridge failed: " + t);
                }
              }

              if (extended == null) return chain.proceed();
              Object[] args = chain.getArgs().toArray();
              args[0] = extended;
              return chain.proceed(args);
            });

    MiniColorBridge26130.install(active, token -> resolver.resolve(null, token), lpparam, tag);

    installed = true;
    Knot.log(tag + ": installed LINE 26.13.0 ThemeManager element bridge");
  }

  static void resetProbe() {
    LOGGED.clear();
    UNMAPPED.clear();
    MINI_OVERRIDDEN.clear();
    MiniColorBridge26130.resetProbe();
  }

  private static Object createThemeValue(
      Constructor<?> colorConstructor,
      Constructor<?> valueConstructor,
      int color,
      ColorSlot slot)
      throws Throwable {
    Object colorValue = colorConstructor.newInstance(ColorStateList.valueOf(color));
    Object[] args = new Object[8];
    putColorSlot(args, colorValue, slot);
    return valueConstructor.newInstance(args);
  }

  private static Object overrideThemeValue(
      Object existing,
      Class<?> valueClass,
      Constructor<?> colorConstructor,
      Constructor<?> valueConstructor,
      int color,
      ColorSlot slot)
      throws Throwable {
    if (existing == null || !valueClass.isInstance(existing)) {
      return createThemeValue(colorConstructor, valueConstructor, color, slot);
    }

    Object[] args = new Object[8];
    String[] fields = {"a", "b", "c", "d", "e", "f", "g", "h"};
    for (int i = 0; i < fields.length; i++) {
      args[i] = Reflect.getObjectField(existing, fields[i]);
    }
    Object colorValue = colorConstructor.newInstance(ColorStateList.valueOf(color));
    putColorSlot(args, colorValue, slot);
    return valueConstructor.newInstance(args);
  }

  private static void putColorSlot(Object[] args, Object colorValue, ColorSlot slot) {
    switch (slot) {
      case IMAGE_TINT:
        args[1] = colorValue;
        break;
      case BACKGROUND:
        args[2] = colorValue;
        break;
      case BACKGROUND_IMAGE_TINT:
        args[4] = colorValue;
        break;
      case TEXT:
        args[5] = colorValue;
        break;
      case SHADOW:
        args[6] = colorValue;
        break;
      case HINT:
        args[7] = colorValue;
        break;
    }
  }

  private static ColorSlot resolveSlot(String element) {
    String name = normalize(element);
    if (name.isEmpty()) return null;

    if (name.contains("backgroundimage") && name.contains("tint")) {
      return ColorSlot.BACKGROUND_IMAGE_TINT;
    }
    if (name.contains("shadow")) return ColorSlot.SHADOW;
    if (name.contains("text")
        || name.contains("label")
        || name.contains("title")
        || name.contains("name")
        || name.contains("caption")
        || name.contains("description")
        || name.contains("value")
        || name.contains("link")) {
      return ColorSlot.TEXT;
    }
    if (name.contains("hint") || name.contains("placeholder")) return ColorSlot.HINT;
    if (name.contains("icon")
        || name.contains("imagetint")
        || name.contains("arrow")
        || name.contains("chevron")) {
      return ColorSlot.IMAGE_TINT;
    }
    if (name.contains("background")
        || name.contains("fill")
        || name.contains("surface")
        || name.contains("container")
        || name.contains("separator")
        || name.contains("divider")
        || name.contains("border")
        || name.contains("outline")) {
      return ColorSlot.BACKGROUND;
    }
    return null;
  }

  private static String identity(String component, String element) {
    return (component == null ? "" : component) + "/" + (element == null ? "" : element);
  }

  private static String normalize(String value) {
    if (value == null) return "";
    return value.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(".", "");
  }
}
