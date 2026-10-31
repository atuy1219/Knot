package app.zipper.knot.hooks;

final class ThemeExtendVersion {

  static final class Config {
    final String parserClass;
    final String parserMethod;
    final String parserConfigClass;
    final String keyClass;

    Config(String parserClass, String parserMethod, String parserConfigClass, String keyClass) {
      this.parserClass = parserClass;
      this.parserMethod = parserMethod;
      this.parserConfigClass = parserConfigClass;
      this.keyClass = keyClass;
    }
  }

  private ThemeExtendVersion() {}

  static Config resolve(String versionName) {
    if (versionName == null) return null;
    if (versionName.startsWith("26.10.1")) return ThemeExtendVersion26101.create();
    if (versionName.startsWith("26.11.0")) return ThemeExtendVersion26110.create();
    if (versionName.startsWith("26.13.0")) return ThemeExtendVersion26130.create();
    return null;
  }
}
