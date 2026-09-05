package app.zipper.knot.hooks;

import android.content.Context;
import app.zipper.knot.LineVersion;
import app.zipper.knot.Reflect;
import java.io.File;
import org.json.JSONObject;

final class LineStickerGlideMediaResolver {
  private LineStickerGlideMediaResolver() {}

  static NotificationMediaFileStore.Attachment acquire(
      Context context, StickerMetadata metadata, String notificationStickerUrl) {
    if (context == null || metadata == null) return null;

    if (metadata.isArrangedSticker()) {
      return LineCombinationStickerMediaResolver.acquire(context, metadata.combinationStickerId);
    }

    if (!hasText(notificationStickerUrl) || !notificationStickerUrl.startsWith("https://")) {
      return null;
    }
    return attachmentFromFile(context, LineGlideMediaUtils.requestFile(context, notificationStickerUrl));
  }

  static File requestStickerFile(Context context, StickerPart sticker) {
    if (context == null || sticker == null || sticker.packageId <= 0L) return null;

    LineVersion.Config version = LineVersion.get();
    if (version == null) return null;
    LineVersion.Config.Notification config = version.notification;
    if (!hasText(config.stickerUrlBuilderClass)) return null;
    try {
      String url = buildLineStickerUrl(context, sticker, config);
      if (!hasText(url) || !url.startsWith("https://")) return null;
      return LineGlideMediaUtils.requestFile(context, url);
    } catch (Throwable ignored) {
      return null;
    }
  }

  static StickerMetadata parse(String parameter) {
    String combinationStickerId = null;
    if (hasText(parameter)) {
      try {
        combinationStickerId = stringValue(new JSONObject(parameter), "CSSTKID");
      } catch (Throwable ignored) {
      }
    }
    return new StickerMetadata(combinationStickerId);
  }

  private static NotificationMediaFileStore.Attachment attachmentFromFile(
      Context context, File file) {
    if (file == null || !file.isFile() || file.length() <= 0L) return null;
    String mimeType = LineGlideMediaUtils.sniffMime(file);
    if (!LineGlideMediaUtils.isImage(mimeType)) return null;
    return NotificationMediaFileStore.fromExistingFile(context, file, mimeType);
  }

  private static String buildLineStickerUrl(
      Context context, StickerPart sticker, LineVersion.Config.Notification config)
      throws Exception {
    Class<?> builderClass =
        Reflect.findClass(config.stickerUrlBuilderClass, context.getClassLoader());
    Object builder = Reflect.findConstructorExact(builderClass).newInstance();
    Reflect.findMethodExact(builderClass, config.stickerInitializeMethod, Context.class)
        .invoke(builder, context);

    if (hasText(sticker.hash)) {
      String base =
          (String)
              Reflect.findMethodExact(
                      builderClass,
                      config.stickerV2UrlMethod,
                      long.class,
                      String.class,
                      String.class,
                      String.class)
                  .invoke(builder, sticker.stickerId, sticker.hash, null, "sticker.png");
      return (String)
          Reflect.findMethodExact(
                  builderClass, config.stickerVersionUrlMethod, long.class, String.class)
              .invoke(null, sticker.packageVersion, base);
    }

    return (String)
        Reflect.findMethodExact(
                builderClass,
                config.stickerPackageUrlMethod,
                long.class,
                long.class,
                String[].class)
            .invoke(
                builder,
                sticker.packageId,
                sticker.packageVersion,
                (Object) new String[] {"stickers", sticker.stickerId + ".png"});
  }

  private static String stringValue(JSONObject json, String key) {
    Object value = json.opt(key);
    if (value == null || value == JSONObject.NULL) return null;
    String text = String.valueOf(value);
    return hasText(text) ? text : null;
  }

  private static boolean hasText(String value) {
    return value != null && !value.isEmpty();
  }

  static final class StickerMetadata {
    final String combinationStickerId;

    StickerMetadata(String combinationStickerId) {
      this.combinationStickerId = combinationStickerId;
    }

    boolean isArrangedSticker() {
      return hasText(combinationStickerId);
    }
  }

  static final class StickerPart {
    final long stickerId;
    final long packageId;
    final long packageVersion;
    final String hash;

    StickerPart(long stickerId, long packageId, long packageVersion, String hash) {
      this.stickerId = stickerId;
      this.packageId = packageId;
      this.packageVersion = packageVersion;
      this.hash = hash;
    }
  }
}
