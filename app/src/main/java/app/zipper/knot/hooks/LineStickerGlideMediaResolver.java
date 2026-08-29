package app.zipper.knot.hooks;

import android.content.Context;
import app.zipper.knot.LineVersion;
import java.io.File;
import java.lang.reflect.Method;
import org.json.JSONObject;

final class LineStickerGlideMediaResolver {
  private LineStickerGlideMediaResolver() {}

  static NotificationMediaFileStore.Attachment acquire(
      Context context,
      NotificationMediaCaptureStore.MessageData captured,
      StickerMetadata metadata) {
    if (context == null || metadata == null) return null;
    if (metadata.isEmojiLike() && captured != null && captured.decryptedMessage != null) {
      NotificationMediaFileStore.Attachment direct =
          acquireFromMessage(context, captured.decryptedMessage);
      if (direct != null) return direct;
    }
    if (metadata.packageId <= 0L) return null;

    LineVersion.Config version = LineVersion.get();
    if (version == null) return null;
    LineVersion.Config.Notification config = version.notification;
    if (!hasText(config.stickerUrlBuilderClass)) return null;
    try {
      String url = buildLineStickerUrl(context, metadata, config);
      if (!hasText(url) || !url.startsWith("https://")) return null;
      File file = LineGlideMediaUtils.requestFile(context, url);
      return attachmentFromFile(context, file);
    } catch (Throwable ignored) {
      return null;
    }
  }

  static StickerMetadata parse(String parameter) {
    if (!hasText(parameter)) return null;
    try {
      JSONObject json = new JSONObject(parameter);
      long stickerId = firstLong(json, "STKID", "stickerId", "sticker_id");
      if (stickerId <= 0L) return null;
      return new StickerMetadata(
          stickerId,
          firstLong(json, "STKPKGID", "stickerPackageId", "sticker_package_id"),
          firstLong(json, "STKVER", "stickerPackageVer", "sticker_package_ver"),
          firstString(json, "STKHASH", "stickerHash", "sticker_hash"));
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static NotificationMediaFileStore.Attachment acquireFromMessage(
      Context context, Object message) {
    try {
      return attachmentFromFile(context, LineGlideMediaUtils.requestFile(context, message));
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static NotificationMediaFileStore.Attachment attachmentFromFile(
      Context context, File file) {
    if (file == null || !file.isFile() || file.length() <= 0L) return null;
    String mimeType = LineGlideMediaUtils.sniffMime(file);
    if (!LineGlideMediaUtils.isImage(mimeType)) return null;
    return NotificationMediaFileStore.fromExistingFile(context, file, mimeType);
  }

  private static String buildLineStickerUrl(
      Context context, StickerMetadata metadata, LineVersion.Config.Notification config)
      throws Exception {
    Class<?> builderClass =
        Class.forName(config.stickerUrlBuilderClass, false, context.getClassLoader());
    Object builder = builderClass.getDeclaredConstructor().newInstance();
    builderClass.getMethod(config.stickerInitializeMethod, Context.class).invoke(builder, context);

    if (hasText(metadata.hash)) {
      Method v2 =
          builderClass.getMethod(
              config.stickerV2UrlMethod, long.class, String.class, String.class, String.class);
      String base =
          (String) v2.invoke(builder, metadata.stickerId, metadata.hash, null, "sticker.png");
      return (String)
          builderClass
              .getMethod(config.stickerVersionUrlMethod, long.class, String.class)
              .invoke(null, metadata.packageVersion, base);
    }

    return (String)
        builderClass
            .getMethod(config.stickerPackageUrlMethod, long.class, long.class, String[].class)
            .invoke(
                builder,
                metadata.packageId,
                metadata.packageVersion,
                (Object) new String[] {"stickers", metadata.stickerId + ".png"});
  }

  private static long firstLong(JSONObject json, String... keys) {
    for (String key : keys) {
      Object value = json.opt(key);
      if (value == null || value == JSONObject.NULL) continue;
      try {
        return Long.parseLong(String.valueOf(value));
      } catch (NumberFormatException ignored) {
      }
    }
    return -1L;
  }

  private static String firstString(JSONObject json, String... keys) {
    for (String key : keys) {
      Object value = json.opt(key);
      if (value == null || value == JSONObject.NULL) continue;
      String text = String.valueOf(value);
      if (hasText(text)) return text;
    }
    return null;
  }

  private static boolean hasText(String value) {
    return value != null && !value.isEmpty();
  }

  static final class StickerMetadata {
    final long stickerId;
    final long packageId;
    final long packageVersion;
    final String hash;

    StickerMetadata(long stickerId, long packageId, long packageVersion, String hash) {
      this.stickerId = stickerId;
      this.packageId = packageId;
      this.packageVersion = packageVersion;
      this.hash = hash;
    }

    boolean isEmojiLike() {
      return packageId <= 0L || packageId == 5L;
    }
  }
}
