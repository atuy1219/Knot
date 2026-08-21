package app.zipper.knot.hooks;

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import app.zipper.knot.Knot;
import app.zipper.knot.Reflect;
import io.github.libxposed.api.XposedInterface.Hooker;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

final class SemanticColorBridge {

  interface Resolver {
    Integer resolve(String token);
  }

  private static final String SEMANTIC_SECTION = "theme.semantic";
  private static final String SEMANTIC_SUFFIX = ".background.color";
  private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();
  private static volatile boolean installed;

  private SemanticColorBridge() {}

  static synchronized void install(
      BooleanSupplier active, Resolver resolver, String tag, boolean probe) {
    PROVIDERS.add(new Provider(active, resolver, tag, probe));
    if (installed) return;
    installed = true;

    Hooker colorHook =
        chain -> {
          Integer color = resolve((Resources) chain.getThisObject(), (Integer) chain.getArg(0));
          return color != null ? color : chain.proceed();
        };
    Hooker colorStateListHook =
        chain -> {
          Integer color = resolve((Resources) chain.getThisObject(), (Integer) chain.getArg(0));
          return color != null ? ColorStateList.valueOf(color) : chain.proceed();
        };

    Knot.module
        .hook(Reflect.findMethodExact(Resources.class, "getColor", int.class))
        .intercept(colorHook);
    Knot.module
        .hook(
            Reflect.findMethodExact(Resources.class, "getColor", int.class, Resources.Theme.class))
        .intercept(colorHook);
    Knot.module
        .hook(Reflect.findMethodExact(Resources.class, "getColorStateList", int.class))
        .intercept(colorStateListHook);
    Knot.module
        .hook(
            Reflect.findMethodExact(
                Resources.class, "getColorStateList", int.class, Resources.Theme.class))
        .intercept(colorStateListHook);
  }

  static void resetProbe(String tag) {
    for (Provider provider : PROVIDERS) {
      if (provider.tag.equals(tag)) {
        provider.logged.clear();
        provider.unmapped.clear();
      }
    }
  }

  static Map<String, Integer> parseThemeSemantic(JSONObject root, Set<String> skipTokens) {
    JSONObject semantic = root.optJSONObject(SEMANTIC_SECTION);
    if (semantic == null) return null;

    Map<String, Integer> map = new HashMap<>();
    for (Iterator<String> it = semantic.keys(); it.hasNext(); ) {
      String key = it.next();
      if (!key.endsWith(SEMANTIC_SUFFIX)) continue;
      String token = key.substring(0, key.length() - SEMANTIC_SUFFIX.length());
      if (skipTokens != null && skipTokens.contains(token)) continue;
      Integer color = parseColor(semantic.opt(key));
      if (color != null) map.put(token, color);
    }
    return map;
  }

  private static Integer resolve(Resources resources, int id) {
    String token = entryName(resources, id);
    if (token == null) return null;

    for (Provider provider : PROVIDERS) {
      boolean active;
      try {
        active = provider.active.getAsBoolean();
      } catch (Throwable ignored) {
        continue;
      }
      if (!active) continue;

      Integer color;
      try {
        color = provider.resolver.resolve(token);
      } catch (Throwable t) {
        Knot.log(provider.tag + ": semantic resolver failed for " + token + ": " + t);
        continue;
      }

      if (color != null) {
        if (provider.probe && provider.logged.add(token)) {
          Knot.log(provider.tag + ": semantic " + token + " -> " + hex(color));
        }
        return color;
      }

      if (provider.probe
          && provider.unmapped.size() < 160
          && looksSemantic(token)
          && provider.unmapped.add(token)) {
        Knot.log(provider.tag + ": unmapped semantic " + token);
      }
    }
    return null;
  }

  private static String entryName(Resources resources, int id) {
    if ((id >>> 24) != 0x7f) return null;
    try {
      if (!"jp.naver.line.android".equals(resources.getResourcePackageName(id))) return null;
      return resources.getResourceEntryName(id);
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static boolean looksSemantic(String token) {
    String name = token.toLowerCase(Locale.ROOT);
    return name.contains("text")
        || name.contains("fill")
        || name.contains("background")
        || name.contains("surface")
        || name.contains("separator")
        || name.contains("divider")
        || name.contains("border")
        || name.contains("outline")
        || name.contains("icon")
        || name.contains("link")
        || name.contains("accent")
        || name.contains("prominent")
        || name.contains("neutral");
  }

  private static Integer parseColor(Object value) {
    if (value instanceof String) return parseColor((String) value);
    if (value instanceof JSONObject) {
      JSONObject states = (JSONObject) value;
      for (String state : new String[] {"normal", "default", "selected", "pressed"}) {
        Integer color = parseColor(states.optString(state, null));
        if (color != null) return color;
      }
    }
    return null;
  }

  private static Integer parseColor(String value) {
    if (value == null || value.isEmpty()) return null;
    try {
      return Color.parseColor(value);
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static String hex(int color) {
    return String.format(Locale.ROOT, "#%08X", color);
  }

  private static final class Provider {
    final BooleanSupplier active;
    final Resolver resolver;
    final String tag;
    final boolean probe;
    final Set<String> logged = java.util.concurrent.ConcurrentHashMap.newKeySet();
    final Set<String> unmapped = java.util.concurrent.ConcurrentHashMap.newKeySet();

    Provider(BooleanSupplier active, Resolver resolver, String tag, boolean probe) {
      this.active = active;
      this.resolver = resolver;
      this.tag = tag;
      this.probe = probe;
    }
  }
}
