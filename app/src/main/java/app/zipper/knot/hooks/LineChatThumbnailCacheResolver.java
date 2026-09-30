package app.zipper.knot.hooks;

import android.content.Context;
import app.zipper.knot.Knot;
import app.zipper.knot.LineVersion;
import app.zipper.knot.Reflect;
import java.io.File;

final class LineChatThumbnailCacheResolver {
  private static volatile Object lineContentCache;

  private LineChatThumbnailCacheResolver() {}

  static NotificationMediaFileStore.Attachment acquire(
      Context context, NotificationMediaThumbnailCacheStore.Key key, boolean cacheOnly) {
    if (context == null || key == null) return null;
    LineVersion.Config version = LineVersion.get();
    if (version == null) return null;
    LineVersion.Config.Notification config = version.notification;

    try {
      File file;
      if (cacheOnly) {
        file = lineThumbnailCacheFile(context, key, config);
      } else {
        Object request = buildThumbnailRequest(context, key, config);
        file = LineGlideMediaUtils.requestFile(context, request);
      }
      if (file == null || !file.isFile() || file.length() <= 0L) return null;
      String mimeType = LineGlideMediaUtils.sniffMime(file);
      if (!LineGlideMediaUtils.isImage(mimeType)) return null;
      return NotificationMediaFileStore.fromExistingFile(context, file, mimeType);
    } catch (Throwable t) {
      Knot.log(
          "Knot: notification media thumbnail acquisition failed (messageId="
              + key.serverMessageId
              + ")",
          t);
      return null;
    }
  }

  private static Object buildThumbnailRequest(
      Context context,
      NotificationMediaThumbnailCacheStore.Key key,
      LineVersion.Config.Notification config)
      throws Exception {
    ClassLoader loader = context.getClassLoader();
    Class<?> requestClass = Reflect.findClass(config.messageThumbnailRequestClass, loader);
    Class<?> encryptionClass = Reflect.findClass(config.messageObsEncryptionDataClass, loader);
    return Reflect.findConstructorExact(
            requestClass,
            String.class,
            String.class,
            long.class,
            String.class,
            encryptionClass,
            String.class,
            boolean.class)
        .newInstance(
            key.chatId,
            key.serverMessageId,
            key.localMessageId,
            null,
            key.obsEncryptionData,
            key.obsPopInfo,
            key.isSquare);
  }

  private static File lineThumbnailCacheFile(
      Context context,
      NotificationMediaThumbnailCacheStore.Key key,
      LineVersion.Config.Notification config)
      throws Exception {
    ClassLoader loader = context.getClassLoader();
    Class<?> cacheClass = Reflect.findClass(config.messageContentCacheClass, loader);
    Object cache = lineContentCache;
    if (cache == null || !cacheClass.isInstance(cache)) {
      synchronized (LineChatThumbnailCacheResolver.class) {
        cache = lineContentCache;
        if (cache == null || !cacheClass.isInstance(cache)) {
          cache = Reflect.findConstructorExact(cacheClass).newInstance();
          Reflect.findMethodExact(
                  cacheClass, config.messageContentCacheInitializeMethod, Context.class)
              .invoke(cache, context);
          lineContentCache = cache;
        }
      }
    }

    Class<?> keyClass = Reflect.findClass(config.messageContentCacheKeyClass, loader);
    Object contentKey =
        Reflect.findConstructorExact(keyClass, String.class, long.class)
            .newInstance(key.chatId, key.localMessageId);
    Object result =
        Reflect.findMethodExact(cacheClass, config.messageThumbnailCacheFileMethod, keyClass)
            .invoke(cache, contentKey);
    return result instanceof File ? (File) result : null;
  }
}
