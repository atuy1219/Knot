package app.zipper.knot.hooks;

import android.content.Context;
import app.zipper.knot.LineVersion;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

final class LineChatGlideMediaResolver {
  private static final int MAX_ATTEMPTS = 3;
  private static final long RETRY_DELAY_MS = 250L;

  private LineChatGlideMediaResolver() {}

  static NotificationMediaFileStore.Attachment acquire(Context context, String messageId) {
    if (context == null || !hasText(messageId)) return null;
    LineVersion.Config version = LineVersion.get();
    if (version == null) return null;
    LineVersion.Config.Notification config = version.notification;
    if (!hasText(config.chatImageSourceClass)) return null;

    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      try {
        Object model = buildLineChatImageModel(context, messageId, config);
        if (model != null) {
          File file = LineGlideMediaUtils.requestFile(context, model);
          if (file != null && file.isFile() && file.length() > 0L) {
            String mimeType = LineGlideMediaUtils.sniffMime(file);
            if (LineGlideMediaUtils.isImage(mimeType)) {
              NotificationMediaFileStore.Attachment attachment =
                  NotificationMediaFileStore.fromExistingFile(context, file, mimeType);
              if (attachment != null) return attachment;
            }
          }
        }
      } catch (Throwable ignored) {
      }
      if (attempt + 1 < MAX_ATTEMPTS && !sleep()) return null;
    }
    return null;
  }

  private static Object buildLineChatImageModel(
      Context context, String messageId, LineVersion.Config.Notification config) throws Throwable {
    ClassLoader loader = context.getClassLoader();
    Class<?> sourceClass = Class.forName(config.chatImageSourceClass, false, loader);
    Object lineSource = sourceClass.getMethod("valueOf", String.class).invoke(null, "LINE");

    Class<?> copyInfoClass = Class.forName(config.chatImageCopyInfoClass, false, loader);
    Constructor<?> constructor = copyInfoClass.getDeclaredConstructor(String.class, sourceClass);
    constructor.setAccessible(true);
    Object copyInfo = constructor.newInstance(messageId, lineSource);

    Class<?> bridgeHolder = Class.forName(config.chatImageBridgeHolderClass, false, loader);
    Method bridgeGetter = bridgeHolder.getDeclaredMethod(config.chatImageBridgeGetterMethod);
    bridgeGetter.setAccessible(true);
    Object bridge = bridgeGetter.invoke(null);
    if (bridge == null) return null;

    Method requestBuilder =
        bridge
            .getClass()
            .getDeclaredMethod(
                config.chatImageRequestBuilderMethod, Context.class, copyInfoClass, boolean.class);
    requestBuilder.setAccessible(true);
    return requestBuilder.invoke(bridge, context, copyInfo, false);
  }

  private static boolean sleep() {
    try {
      Thread.sleep(RETRY_DELAY_MS);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static boolean hasText(String value) {
    return value != null && !value.isEmpty();
  }
}
