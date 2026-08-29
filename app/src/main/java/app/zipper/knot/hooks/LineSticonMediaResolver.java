package app.zipper.knot.hooks;

import android.content.Context;
import java.io.File;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

final class LineSticonMediaResolver {
  private LineSticonMediaResolver() {}

  static NotificationMediaFileStore.Attachment acquire(
      Context context, NotificationMediaCaptureStore.MessageData captured) {
    if (context == null || captured == null) return null;
    SticonSpec spec = parseSingleSticon(captured.text, captured.parameter);
    if (spec == null) return null;
    String url = buildUrl(spec);
    if (!hasText(url)) return null;
    File file = LineGlideMediaUtils.requestFile(context, url);
    if (file == null || !file.isFile() || file.length() <= 0L) return null;
    String mimeType = LineGlideMediaUtils.sniffMime(file);
    if (!LineGlideMediaUtils.isImage(mimeType)) return null;
    return NotificationMediaFileStore.fromExistingFile(context, file, mimeType);
  }

  private static SticonSpec parseSingleSticon(String text, String parameter) {
    if (!hasText(text) || !hasText(parameter)) return null;
    try {
      Object replaceValue = new JSONObject(parameter).opt("REPLACE");
      if (replaceValue == null || replaceValue == JSONObject.NULL) return null;
      String replaceJson = String.valueOf(replaceValue);
      if (!hasText(replaceJson)) return null;

      JSONObject sticon = new JSONObject(replaceJson).optJSONObject("sticon");
      if (sticon == null) return null;
      JSONArray resources = sticon.optJSONArray("resources");
      if (resources == null || resources.length() != 1) return null;
      JSONObject resource = resources.optJSONObject(0);
      if (resource == null
          || resource.optInt("S", -1) != 0
          || resource.optInt("E", -1) != text.length()) return null;

      String productId = resource.optString("productId", null);
      String sticonId = resource.optString("sticonId", null);
      if (!hasText(productId) || !hasText(sticonId)) return null;
      return new SticonSpec(
          productId,
          sticonId,
          resource.optInt("version", 0),
          resource.optString("resourceType", "STATIC"));
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static String buildUrl(SticonSpec spec) {
    String base =
        "https://stickershop.line-scdn.net/sticonshop/v1/sticon/"
            + spec.productId
            + "/iPhone/"
            + spec.sticonId;
    if (spec.resourceType.toUpperCase(Locale.ROOT).contains("ANIMATION")) {
      return base + "_animation.png";
    }
    return base + ".png" + (spec.version > 0 ? "?v=" + spec.version : "");
  }

  private static boolean hasText(String value) {
    return value != null && !value.isEmpty();
  }

  private static final class SticonSpec {
    final String productId;
    final String sticonId;
    final int version;
    final String resourceType;

    SticonSpec(String productId, String sticonId, int version, String resourceType) {
      this.productId = productId;
      this.sticonId = sticonId;
      this.version = version;
      this.resourceType = resourceType;
    }
  }
}
