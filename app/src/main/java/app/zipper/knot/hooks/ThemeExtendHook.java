package app.zipper.knot.hooks;

import android.graphics.Color;
import android.util.DisplayMetrics;
import app.zipper.knot.Knot;
import app.zipper.knot.KnotConfig;
import app.zipper.knot.LineVersion;
import app.zipper.knot.LoadParam;
import app.zipper.knot.Main;
import app.zipper.knot.Reflect;
import app.zipper.knot.SettingsStore;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import org.json.JSONObject;

public class ThemeExtendHook implements BaseHook {

  private static final String TAG = "Knot: ThemeExtend";
  private static volatile Palette palette;

  @Override
  public void hook(KnotConfig config, LoadParam lpparam) throws Throwable {
    if (!config.extendTheme.enabled || config.useAmoledTheme.enabled) return;

    String versionName = LineVersion.getVersionName(SettingsStore.getContext());
    ThemeExtendVersion.Config version = ThemeExtendVersion.resolve(versionName);
    if (version == null) {
      Knot.log(TAG + ": unsupported LINE version " + versionName);
      return;
    }

    installThemeParser(version, lpparam);
    SemanticColorBridge.install(
        ThemeExtendHook::enabled,
        token -> {
          Palette current = palette;
          return current == null ? null : current.resolveResource(token);
        },
        TAG,
        true);
    Knot.log(TAG + ": installed semantic resource bridge for LINE " + versionName);
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

    Knot.module
        .hook(parser)
        .intercept(
            chain -> {
              Object result = chain.proceed();
              if (!enabled()) return result;

              Palette parsed = parsePalette((String) chain.getArg(0));
              if (parsed != null) {
                palette = parsed;
                SemanticColorBridge.resetProbe(TAG);
                Knot.log(
                    TAG
                        + ": palette semantic="
                        + parsed.semantic.size()
                        + " background="
                        + hex(parsed.background)
                        + " mainText="
                        + hex(parsed.mainText)
                        + " subText="
                        + hex(parsed.messageText)
                        + " divider="
                        + hex(parsed.divider)
                        + " icon="
                        + hex(parsed.icon));
              }
              return result;
            });
  }

  private static boolean enabled() {
    return Main.options.extendTheme.enabled && !Main.options.useAmoledTheme.enabled;
  }

  private static Palette parsePalette(String json) {
    if (json == null || json.isEmpty()) return null;
    try {
      JSONObject root = new JSONObject(json);
      Map<String, Integer> semantic =
          SemanticColorBridge.parseThemeSemantic(root, Collections.emptySet());
      if (semantic == null) semantic = Collections.emptyMap();

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

      if (background == null) {
        background = firstSemantic(semantic, "primaryBackground", "basicBackground", "background");
      }
      if (mainText == null) mainText = firstSemantic(semantic, "primaryText", "basicText");
      if (background == null && mainText == null && semantic.isEmpty()) return null;

      if (background == null) background = Color.BLACK;
      if (mainText == null) mainText = Color.WHITE;

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
      Integer divider =
          firstColor(
              root,
              "list.common",
              "divider.background.color",
              "list.common",
              "separator.background.color",
              "friendlist.item",
              "contents.outline.color");
      Integer icon =
          firstColor(
              root,
              "list.common",
              "action.icon.color",
              "list.common",
              "arrow.icon.color",
              "friendlist.item",
              "home.icon.color");
      Integer destructiveBackground =
          firstColor(root, "theme.variable", "destructiveButton_background");
      Integer destructiveText = firstColor(root, "theme.variable", "destructiveButton_text");

      if (messageText == null) {
        messageText = firstSemantic(semantic, "secondaryText", "tertiaryText", "primarySubText");
      }
      if (dimmedText == null) {
        dimmedText = firstSemantic(semantic, "tertiaryText", "quaternaryText", "placeholderText");
      }
      if (timeText == null) {
        timeText = firstSemantic(semantic, "quaternaryText", "quinaryText", "secondarySubText");
      }
      if (highlightText == null) {
        highlightText = firstSemantic(semantic, "primaryLink", "primaryAccentText", "accentText");
      }
      if (divider == null) {
        divider = firstSemantic(semantic, "primarySeparator", "secondarySeparator", "primaryBorder");
      }
      if (icon == null) icon = firstSemantic(semantic, "primaryIcon", "secondaryIcon");
      if (destructiveBackground == null) {
        destructiveBackground = firstSemantic(semantic, "prominentFill", "prominentText");
      }
      if (destructiveText == null) destructiveText = firstSemantic(semantic, "onProminentFill");

      if (messageText == null) messageText = mainText;
      if (dimmedText == null) dimmedText = messageText;
      if (timeText == null) timeText = dimmedText;
      if (highlightText == null) highlightText = mainText;
      if (divider == null) divider = mix(background, mainText, 0.14f);
      if (icon == null) icon = mainText;
      if (destructiveBackground == null) destructiveBackground = highlightText;
      if (destructiveText == null) destructiveText = mainText;

      return new Palette(
          semantic,
          background,
          mainText,
          dimmedText,
          messageText,
          timeText,
          highlightText,
          divider,
          icon,
          destructiveBackground,
          destructiveText);
    } catch (Throwable t) {
      Knot.log(TAG + ": palette parse failed: " + t);
      return null;
    }
  }

  private static Integer firstSemantic(Map<String, Integer> semantic, String... keys) {
    for (String key : keys) {
      Integer color = semantic.get(key);
      if (color != null) return color;
    }
    return null;
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

  private static int mix(int background, int foreground, float amount) {
    float a = Math.max(0f, Math.min(1f, amount));
    return Color.argb(
        Math.round(Color.alpha(background) + (Color.alpha(foreground) - Color.alpha(background)) * a),
        Math.round(Color.red(background) + (Color.red(foreground) - Color.red(background)) * a),
        Math.round(Color.green(background) + (Color.green(foreground) - Color.green(background)) * a),
        Math.round(Color.blue(background) + (Color.blue(foreground) - Color.blue(background)) * a));
  }

  private static String normalize(String name) {
    if (name == null) return "";
    return name.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
  }

  private static String hex(Integer color) {
    return color == null ? "null" : String.format(Locale.ROOT, "#%08X", color);
  }

  private static final class Palette {
    final Map<String, Integer> semantic;
    final int background;
    final int mainText;
    final int dimmedText;
    final int messageText;
    final int timeText;
    final int highlightText;
    final int divider;
    final int icon;
    final int destructiveBackground;
    final int destructiveText;

    Palette(
        Map<String, Integer> semantic,
        int background,
        int mainText,
        int dimmedText,
        int messageText,
        int timeText,
        int highlightText,
        int divider,
        int icon,
        int destructiveBackground,
        int destructiveText) {
      this.semantic = semantic;
      this.background = background;
      this.mainText = mainText;
      this.dimmedText = dimmedText;
      this.messageText = messageText;
      this.timeText = timeText;
      this.highlightText = highlightText;
      this.divider = divider;
      this.icon = icon;
      this.destructiveBackground = destructiveBackground;
      this.destructiveText = destructiveText;
    }

    Integer resolveResource(String resourceName) {
      Integer exact = semantic.get(resourceName);
      if (exact != null) return exact;

      String name = normalize(resourceName);
      if (name.isEmpty()) return null;

      if (name.contains("onprominent")) return destructiveText;
      if (name.contains("prominenttext") || name.contains("prominentfill")) {
        return destructiveBackground;
      }
      if (name.contains("destructive")
          || name.contains("danger")
          || name.contains("negative")
          || name.contains("error")) {
        return destructiveBackground;
      }

      if (name.contains("text")) {
        if (name.contains("link") || name.contains("accent") || name.contains("highlight")) {
          return highlightText;
        }
        if (name.contains("placeholder") || name.contains("dimmed")) return dimmedText;
        if (name.contains("time") || name.contains("quinary") || name.contains("septenary")) {
          return timeText;
        }
        if (name.contains("secondary")
            || name.contains("tertiary")
            || name.contains("quaternary")
            || name.contains("sub")
            || name.contains("caption")
            || name.contains("description")) {
          return messageText;
        }
        return mainText;
      }

      if (name.contains("icon") || name.contains("arrow") || name.contains("chevron")) {
        if (name.contains("accent") || name.contains("selected") || name.contains("highlight")) {
          return highlightText;
        }
        if (name.contains("secondary") || name.contains("tertiary") || name.contains("sub")) {
          return messageText;
        }
        return icon;
      }

      if (name.contains("separator")
          || name.contains("divider")
          || name.contains("border")
          || name.contains("outline")) {
        return divider;
      }

      if (name.contains("accentfill") || name.contains("highlightfill")) return highlightText;
      if (name.contains("neutralfill") || name.endsWith("fill") || name.contains("surface")) {
        if (name.contains("primary")) return mix(background, mainText, 0.10f);
        if (name.contains("secondary")) return mix(background, mainText, 0.08f);
        if (name.contains("tertiary")) return mix(background, mainText, 0.06f);
        if (name.contains("quaternary")) return mix(background, mainText, 0.04f);
        return mix(background, mainText, 0.07f);
      }

      if (name.contains("background") || name.contains("container")) return background;
      return null;
    }
  }
}
