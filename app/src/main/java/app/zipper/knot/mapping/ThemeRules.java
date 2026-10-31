package app.zipper.knot.mapping;

import app.zipper.knot.LineVersion;
import java.util.ArrayList;
import java.util.List;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

/**
 * Discover LINE's theme internals from structural and literal anchors.
 * No R8 class names are stored in the mapping.
 */
final class ThemeRules {
  static final Mappings.Section[] SECTIONS = {
    ThemeRules::themeJsonReader, ThemeRules::themeElements, ThemeRules::miniRoot
  };

  private ThemeRules() {}

  private static void themeJsonReader(Finder f, LineVersion.Config c) {
    // Found in the theme manager's JSON deserialization method.
    String manager = f.classUsing("ThemeJsonParser");
    MethodData parser = f.methodData(manager,
        MethodMatcher.create().usingEqStrings("ThemeJsonParser"));
    if (parser == null) return;
    List<MethodData> readers = new ArrayList<>();
    for (MethodData invoked : Finder.invokes(parser)) {
      if (invoked.getReturnTypeName().equals("java.lang.String")
          && invoked.getParamTypeNames().equals(java.util.Collections.singletonList("java.io.File"))) {
        readers.add(invoked);
      }
    }
    if (readers.size() != 1) return;
    c.theme.readerClass = readers.get(0).getClassName();
    c.theme.methodReadThemeJson = readers.get(0).getName();
  }

  private static void themeElements(Finder f, LineVersion.Config c) {
    String key = f.classUsing("ThemeElementKey(componentKey=");
    c.theme.keyClass = key;
    c.theme.keyComponentField = f.ctorField(key, 0, "java.lang.String");
    c.theme.keyElementField = f.ctorField(key, 1, "java.lang.String");

    // Package comes from the independently resolved ThemeJsonParser.
    String manager = f.classUsing("ThemeJsonParser");
    MethodData resolver = f.methodDataIn(
        Finder.packageOf(manager),
        MethodMatcher.create().paramTypes("java.util.Map", "java.util.Set"));
    if (resolver == null) return;
    String value = resolver.getReturnTypeName();
    ClassData valueData = f.classData(value);
    if (valueData == null) return;
    for (MethodData ctor : valueData.getMethods()) {
      List<String> types = ctor.getParamTypeNames();
      if (!ctor.isConstructor() || types.size() != 8) continue;
      String image = types.get(0);
      String color = types.get(1);
      if (image.equals(color)) continue;
      if (!types.get(2).equals(color) || !types.get(3).equals(image)
          || !types.get(4).equals(color) || !types.get(5).equals(color)
          || !types.get(6).equals(color) || !types.get(7).equals(color)) continue;
      c.theme.resolverClass = resolver.getClassName();
      c.theme.methodResolveElements = resolver.getName();
      c.theme.valueClass = value;
      c.theme.imageClass = image;
      c.theme.colorClass = color;
      break;
    }
  }

  private static void miniRoot(Finder f, LineVersion.Config c) {
    String pkg = "com.linecorp.line.mini.home.impl.list";
    String composer = c.compose.composerClass;
    if (composer.isEmpty()) return;
    List<MethodData> candidates = new ArrayList<>();
    for (String type : f.classesIn(pkg, ClassMatcher.create()
        .addMethod(MethodMatcher.create().paramCount(13)))) {
      for (MethodData method : f.declaredMethods(type, MethodMatcher.create().paramCount(13))) {
        List<String> params = method.getParamTypeNames();
        if (method.getReturnTypeName().equals("void")
            && params.size() == 13 && params.get(10).equals(composer)
            && params.get(11).equals("int") && params.get(12).equals("int")
            && params.get(0).startsWith("com.linecorp.line.mini.")) {
          candidates.add(method);
        }
      }
    }
    if (candidates.size() == 1) {
      c.theme.miniRootClass = candidates.get(0).getClassName();
      c.theme.methodMiniRoot = candidates.get(0).getName();
    }
  }
}
