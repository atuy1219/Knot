package app.zipper.knot.hooks;

import android.content.Context;
import app.zipper.knot.LineVersion;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

final class LineChatGlideMediaResolver {
  private LineChatGlideMediaResolver() {}

  static NotificationMediaFileStore.Attachment acquire(Context context, String messageId) {
    if (context == null || !hasText(messageId)) return null;
    LineVersion.Config version = LineVersion.get();
    if (version == null) return null;
    LineVersion.Config.Notification config = version.notification;
    if (!hasText(config.chatImageSourceClass)) return null;

    try {
      Object model = buildLineChatImageModel(context, messageId, config);
      if (model == null) return null;
      File file = LineGlideMediaUtils.requestFile(context, model);
      if (file == null || !file.isFile() || file.length() <= 0L) return null;
      String mimeType = LineGlideMediaUtils.sniffMime(file);
      if (!LineGlideMediaUtils.isImage(mimeType)) return null;
      return NotificationMediaFileStore.fromExistingFile(context, file, mimeType);
    } catch (Throwable ignored) {
      return null;
    }
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

  private static boolean hasText(String value) {
    return value != null && !value.isEmpty();
  }
}
