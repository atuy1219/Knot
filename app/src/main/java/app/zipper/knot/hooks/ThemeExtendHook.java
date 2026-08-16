package app.zipper.knot.hooks;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.util.DisplayMetrics;
import app.zipper.knot.Knot;
import app.zipper.knot.KnotConfig;
import app.zipper.knot.LineVersion;
import app.zipper.knot.LoadParam;
import app.zipper.knot.Main;
import app.zipper.knot.Reflect;
import app.zipper.knot.SettingsStore;
import io.github.libxposed.api.XposedInterface.Hooker;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;

public class ThemeExtendHook implements BaseHook {

  private static final int[] NO_COLOR = new int[0];
  private static final Map<Integer, int[]> RES_ID_CACHE = new ConcurrentHashMap<>();
  private static volatile Palette palette;

  private static final String[][] ALIASES = {
    {"home.service", "background", "main.view.common", "background", "list.common", "background"},
    {"home.service", "loading.icon", "list.common", "loading.icon", "list.common", "action.icon"},
    {"wallet.header", "background", "friendlist.header", "background", "main.view.common", "background"},
    {"wallet.header", "title.text", "list.common", "name.text", "friendlist.item", "nameText"},
    {"wallet.main", "icon", "list.common", "action.icon", "list.common", "loading.icon"},
    {"wallet.main", "text", "list.common", "name.text", "friendlist.item", "nameText"},
    {"wallet.common", "background", "main.view.common", "background", "list.common", "background"},
    {"wallet.common", "divider.background", "list.common", "divider.background", "list.common", "separator.background"},
    {"wallet.common", "outline.background", "list.common", "divider.background", "list.common", "separator.background"},
    {"wallet.common", "service.outline.background", "list.common", "divider.background", "list.common", "separator.background"},
    {"wallet.common", "service.divider.background", "list.common", "divider.background", "list.common", "separator.background"},
    {"wallet.common", "area.divider.background", "list.common", "divider.background", "list.common", "separator.background"},
    {"wallet.common", "separator.background", "list.common", "separator.background", "list.common", "divider.background"},
    {"wallet.common", "status.separator.background", "list.common", "separator.background", "list.common", "divider.background"},
    {"wallet.common", "title.text", "list.common", "name.text", "friendlist.item", "nameText"},
    {"wallet.common", "text", "list.common", "name.text", "friendlist.item", "nameText"},
    {"wallet.common", "name.text", "list.common", "name.text", "friendlist.item", "nameText"},
    {"wallet.common", "point.text", "list.common", "name.text", "friendlist.item", "nameText"},
    {"wallet.common", "value.text", "list.common", "name.text", "friendlist.item", "nameText"},
    {"wallet.common", "button.text", "list.common", "simpleButton.text", "list.common", "name.text"},
    {"wallet.common", "tab.button.selected.text", "list.common", "name.text", "friendlist.item", "nameText"},
    {"wallet.common", "sub.text", "list.common", "description.text", "friendlist.item", "statusText"},
    {"wallet.common", "more.text", "list.common", "description.text", "friendlist.item", "statusText"},
    {"wallet.common", "info.text", "list.common", "description.text", "friendlist.item", "statusText"},
    {"wallet.common", "guide.text", "list.common", "description.text", "friendlist.item", "statusText"},
    {"wallet.common", "hide.text", "list.common", "description.text", "friendlist.item", "statusText"},
    {"wallet.common", "description.text", "list.common", "description.text", "friendlist.item", "statusText"},
    {"wallet.common", "status.text", "list.common", "description.text", "friendlist.item", "statusText"},
    {"wallet.common", "limited.text", "list.common", "description.text", "friendlist.item", "statusText"},
    {"wallet.common", "category.more.text", "list.common", "description.text", "friendlist.item", "statusText"},
    {"wallet.common", "category.tab.text", "list.common", "name.text", "friendlist.item", "nameText"},
    {"wallet.common", "tab.button.text", "list.common", "description.text", "friendlist.item", "statusText"},
    {"wallet.common", "loading.icon", "list.common", "loading.icon", "list.common", "action.icon"},
    {"wallet.common", "blank.icon", "list.common", "action.icon", "list.common", "loading.icon"},
    {"wallet.common", "info.icon", "list.common", "action.icon", "list.common", "arrow.icon"},
    {"wallet.common", "failed.icon", "list.common", "action.icon", "list.common", "loading.icon"},
    {"wallet.common", "option.icon", "list.common", "action.icon", "list.common", "arrow.icon"},
    {"wallet.common", "display.icon", "list.common", "action.icon", "list.common", "loading.icon"},
    {"wallet.common", "service.icon", "list.common", "action.icon", "list.common", "loading.icon"},
    {"wallet.common", "category.icon", "list.common", "action.icon", "list.common", "loading.icon"},
    {"wallet.common", "newDotIcon", "list.common", "action.icon", "list.common", "loading.icon"},
    {"wallet.common", "tab.button.newDotIcon", "list.common", "action.icon", "list.common", "loading.icon"},
    {"wallet.common", "arrow", "list.common", "arrow.icon", "list.common", "action.icon"},
    {"wallet.common", "category.arrow", "list.common", "arrow.icon", "list.common", "action.icon"},
    {"wallet.common", "sub.arrow", "list.common", "arrow.icon", "list.common", "action.icon"},
    {"wallet.common", "third.arrow", "list.common", "arrow.icon", "list.common", "action.icon"},
    {"wallet.common", "reload.icon", "list.common", "loading.icon", "list.common", "action.icon"},
    {"wallet.common", "summary.reload.icon", "list.common", "loading.icon", "list.common", "action.icon"},
    {"wallet.common", "reload.outline", "list.common", "simpleButton.outline", "list.common", "divider.background"},
    {"wallet.common", "summary.reload.outline", "list.common", "simpleButton.outline", "list.common", "divider.background"},
    {"wallet.common", "button.background", "list.common", "simpleButton.background", "list.common", "background"},
    {"wallet.common", "button.outline", "list.common", "simpleButton.outline", "list.common", "divider.background"},
    {"wallet.common", "button.unselected.background", "list.common", "simpleButton.background", "list.common", "background"},
    {"wallet.common", "tab.button.selected.background", "list.common", "simpleButton.background", "list.common", "background"},
    {"wallet.common", "tab.button.outline", "list.common", "simpleButton.outline", "list.common", "divider.background"},
    {"wallet.common", "status.background", "list.common", "simpleButton.background", "list.common", "background"},
    {"wallet.common", "reload.background", "list.common", "simpleButton.background", "list.common", "background"},
    {"wallet.common", "summary.background", "list.common", "background", "main.view.common", "background"},
    {"wallet.common", "selectorBar.background", "list.common", "background", "main.view.common", "background"},
    {"wallet.sub", "officialAccount.text", "list.common", "name.text", "friendlist.item", "nameText"},
    {"wallet.sub", "price.text", "list.common", "name.text", "friendlist.item", "nameText"},
    {"wallet.sub", "point.text", "list.common", "name.text", "friendlist.item", "nameText"},
    {"wallet.sub", "coin.text", "list.common", "name.text", "friendlist.item", "nameText"},
    {"wallet.sub", "ad.action.text", "list.common", "name.text", "friendlist.item", "nameText"},
    {"wallet.sub", "ad.subtext", "list.common", "description.text", "friendlist.item", "statusText"},
    {"wallet.sub", "ad.action.icon", "list.common", "action.icon", "list.common", "arrow.icon"},
    {"wallet.sub", "ad.dot.icon", "list.common", "action.icon", "list.common", "loading.icon"},
    {"wallet.sub", "add.icon", "list.common", "action.icon", "list.common", "loading.icon"},
    {"wallet.sub", "expand.icon", "list.common", "expand.icon", "list.common", "arrow.icon"},
    {"wallet.sub", "expand.outline", "list.common", "expand.icon.outline", "list.common", "divider.background"},
    {"wallet.sub", "ad.button.background", "list.common", "simpleButton.background", "list.common", "background"},
    {"wallet.sub", "ad.button.outline", "list.common", "simpleButton.outline", "list.common", "divider.background"},
    {"wallet.sub", "ad.button.text", "list.common", "simpleButton.text", "list.common", "name.text"},
    {"wallet.sub", "simple.button.background", "list.common", "simpleButton.background", "list.common", "background"},
    {"wallet.sub", "simple.button.outline", "list.common", "simpleButton.outline", "list.common", "divider.background"},
    {"wallet.sub", "simple.button.text", "list.common", "simpleButton.text", "list.common", "name.text"},
    {"wallet.sub", "simple.button.icon", "list.common", "action.icon", "list.common", "loading.icon"}
  };

  @Override
  public void hook(KnotConfig config, LoadParam lpparam) throws Throwable {
    if (!config.extendTheme.enabled || config.useAmoledTheme.enabled) return;

    Context context = SettingsStore.getContext();
    String versionName = LineVersion.getVersionName(context);
    ThemeExtendVersion.Config version = ThemeExtendVersion.resolve(versionName);
    if (version == null) {
      Knot.log("Knot: ThemeExtend: unsupported LINE version " + versionName);
      return;
    }

    installThemeParser(version, lpparam);
    installSemanticColorFallback();
    Knot.log("Knot: ThemeExtend: installed for LINE " + versionName);
  }

  private static void installThemeParser(ThemeExtendVersion.Config version, LoadParam lpparam) {
    Method parser =
        Reflect.findMethodExact(
            version.parserClass,
            lpparam.classLoader,
            version.parserMethod,
            String.class,
            version.parserConfigClass,
            DisplayMetrics.class);
    Class<?> keyClass = Reflect.findClass(version.keyClass, lpparam.classLoader);
    Constructor<?> keyConstructor =
        Reflect.findConstructorExact(keyClass, String.class, String.class);

    Knot.module
        .hook(parser)
        .intercept(
            chain -> {
              Object result = chain.proceed();
              if (!Main.options.extendTheme.enabled || Main.options.useAmoledTheme.enabled) {
                return result;
              }
              if (!(result instanceof Map)) return result;

              String json = (String) chain.getArg(0);
              Palette parsed = parsePalette(json);
              if (parsed != null) {
                palette = parsed;
                RES_ID_CACHE.clear();
              }

              Map<Object, Object> extended = new HashMap<>((Map<?, ?>) result);
              int aliases = extendAliases(extended, keyConstructor);
              if (parsed != null || aliases > 0) {
                Knot.log(
                    "Knot: ThemeExtend: updated theme bridge aliases="
                        + aliases
                        + " semantic="
                        + (parsed != null));
              }
              return extended;
            });
  }

  private static int extendAliases(Map<Object, Object> map, Constructor<?> keyConstructor) {
    int count = 0;
    for (String[] alias : ALIASES) {
      if (putAlias(map, keyConstructor, alias)) count++;
    }
    return count;
  }

  private static boolean putAlias(
      Map<Object, Object> map, Constructor<?> keyConstructor, String[] alias) {
    try {
      Object target = keyConstructor.newInstance(alias[0], alias[1]);
      if (map.containsKey(target)) return false;
      for (int i = 2; i + 1 < alias.length; i += 2) {
        Object source = keyConstructor.newInstance(alias[i], alias[i + 1]);
        Object style = map.get(source);
        if (style != null) {
          map.put(target, style);
          return true;
        }
      }
    } catch (Throwable t) {
      Knot.log("Knot: ThemeExtend: alias failed: " + t);
    }
    return false;
  }

  private static void installSemanticColorFallback() {
    Hooker colorHook =
        chain -> {
          if (Main.options.extendTheme.enabled && !Main.options.useAmoledTheme.enabled) {
            Integer color =
                resolveSemanticColor((Resources) chain.getThisObject(), (Integer) chain.getArg(0));
            if (color != null) return color;
          }
          return chain.proceed();
        };
    Hooker colorStateListHook =
        chain -> {
          if (Main.options.extendTheme.enabled && !Main.options.useAmoledTheme.enabled) {
            Integer color =
                resolveSemanticColor((Resources) chain.getThisObject(), (Integer) chain.getArg(0));
            if (color != null) return ColorStateList.valueOf(color);
          }
          return chain.proceed();
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

  private static Integer resolveSemanticColor(Resources resources, int id) {
    int[] cached = RES_ID_CACHE.get(id);
    if (cached != null) return cached.length == 0 ? null : cached[0];

    Integer color = null;
    Palette current = palette;
    if (current != null) {
      try {
        color = current.resolve(resources.getResourceEntryName(id));
      } catch (Throwable ignored) {
      }
    }
    RES_ID_CACHE.put(id, color == null ? NO_COLOR : new int[] {color});
    return color;
  }

  private static Palette parsePalette(String json) {
    if (json == null || json.isEmpty()) return null;
    try {
      JSONObject root = new JSONObject(json);
      Integer background =
          firstColor(
              root,
              "theme.variable",
              "list_background",
              "main.view.common",
              "background.color",
              "list.common",
              "background.color");
      Integer mainText =
          firstColor(
              root,
              "theme.variable",
              "list_mainText",
              "list.common",
              "name.text.color",
              "friendlist.item",
              "nameText.color");
      if (background == null && mainText == null) return null;

      Integer dimmedText =
          firstColor(
              root,
              "theme.variable",
              "list_mainText_dimmed",
              "theme.variable",
              "list_timeText",
              "list.common",
              "sort.text.color");
      Integer messageText =
          firstColor(
              root,
              "theme.variable",
              "list_messageText",
              "list.common",
              "description.text.color",
              "friendlist.item",
              "statusText.color");
      Integer timeText =
          firstColor(
              root,
              "theme.variable",
              "list_timeText",
              "theme.variable",
              "list_mainText_dimmed",
              "list.common",
              "sort.text.color");
      Integer highlightText =
          firstColor(
              root,
              "theme.variable",
              "list_highlightText",
              "friendlist.item",
              "recommended.link.text.color");
      Integer missedCallText =
          firstColor(
              root,
              "theme.variable",
              "list_missedCall_text",
              "theme.variable",
              "destructiveButton_background");
      Integer destructiveBackground =
          firstColor(root, "theme.variable", "destructiveButton_background");
      Integer destructiveText = firstColor(root, "theme.variable", "destructiveButton_text");

      if (mainText == null) mainText = Color.WHITE;
      if (messageText == null) messageText = mainText;
      if (dimmedText == null) dimmedText = messageText;
      if (timeText == null) timeText = dimmedText;
      if (highlightText == null) highlightText = mainText;
      if (missedCallText == null) missedCallText = destructiveBackground;
      if (missedCallText == null) missedCallText = highlightText;
      if (destructiveBackground == null) destructiveBackground = missedCallText;
      if (destructiveText == null) destructiveText = mainText;

      return new Palette(
          background,
          mainText,
          dimmedText,
          messageText,
          timeText,
          highlightText,
          missedCallText,
          destructiveBackground,
          destructiveText);
    } catch (Throwable t) {
      Knot.log("Knot: ThemeExtend: palette parse failed: " + t);
      return null;
    }
  }

  private static Integer firstColor(JSONObject root, String... sectionKeyPairs) {
    for (int i = 0; i + 1 < sectionKeyPairs.length; i += 2) {
      Integer color = themeColor(root, sectionKeyPairs[i], sectionKeyPairs[i + 1]);
      if (color != null) return color;
    }
    return null;
  }

  private static Integer themeColor(JSONObject root, String section, String key) {
    JSONObject object = root.optJSONObject(section);
    if (object == null || !object.has(key)) return null;
    Object value = object.opt(key);
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

  private static int withAlpha(int color, int percent) {
    int alpha = Math.max(0, Math.min(255, Math.round(255f * percent / 100f)));
    return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
  }

  private static final class Palette {
    final Integer background;
    final int mainText;
    final int dimmedText;
    final int messageText;
    final int timeText;
    final int highlightText;
    final int missedCallText;
    final int destructiveBackground;
    final int destructiveText;

    Palette(
        Integer background,
        int mainText,
        int dimmedText,
        int messageText,
        int timeText,
        int highlightText,
        int missedCallText,
        int destructiveBackground,
        int destructiveText) {
      this.background = background;
      this.mainText = mainText;
      this.dimmedText = dimmedText;
      this.messageText = messageText;
      this.timeText = timeText;
      this.highlightText = highlightText;
      this.missedCallText = missedCallText;
      this.destructiveBackground = destructiveBackground;
      this.destructiveText = destructiveText;
    }

    Integer resolve(String resourceName) {
      if (resourceName == null || resourceName.isEmpty()) return null;

      String name = resourceName;
      int alpha = -1;
      int split = name.lastIndexOf("_alpha");
      if (split > 0 && split + 6 < name.length()) {
        try {
          alpha = Integer.parseInt(name.substring(split + 6));
          name = name.substring(0, split);
        } catch (NumberFormatException ignored) {
        }
      }

      Integer color = resolveBase(name);
      if (color == null) return null;
      return alpha >= 0 ? withAlpha(color, alpha) : color;
    }

    private Integer resolveBase(String name) {
      if (name.endsWith("Link")) return highlightText;
      if ("prominentText".equals(name)) return missedCallText;
      if ("prominentFill".equals(name) || "secondaryProminentFill".equals(name)) {
        return destructiveBackground;
      }
      if ("onProminentFill".equals(name) || "onSecondaryProminentFill".equals(name)) {
        return destructiveText;
      }
      if (name.contains("AccentText")) {
        return name.startsWith("primary") ? mainText : highlightText;
      }
      if (name.contains("PlaceholderText")) return dimmedText;
      if (!name.endsWith("Text")) return null;

      if (name.startsWith("quinary")
          || name.startsWith("septenary")
          || name.startsWith("denary")
          || name.contains("NeutralText")) {
        return timeText;
      }
      if (name.startsWith("secondary")
          || name.startsWith("tertiary")
          || name.startsWith("quaternary")
          || name.startsWith("octonary")
          || name.startsWith("senaryAlt")) {
        return messageText;
      }
      return mainText;
    }
  }
}
