package app.zipper.knot.hooks;

import android.content.Context;
import app.zipper.knot.LineVersion;
import app.zipper.knot.Reflect;
import java.io.File;
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
      Context context, String messageId, LineVersion.Config.Notification config) throws Exception {
    ClassLoader loader = context.getClassLoader();
    Class<?> sourceClass = Reflect.findClass(config.chatImageSourceClass, loader);
    Object lineSource =
        Reflect.findMethodExact(sourceClass, "valueOf", String.class).invoke(null, "LINE");

    Class<?> copyInfoClass = Reflect.findClass(config.chatImageCopyInfoClass, loader);
    Object copyInfo =
        Reflect.findConstructorExact(copyInfoClass, String.class, sourceClass)
            .newInstance(messageId, lineSource);

    Class<?> bridgeHolder = Reflect.findClass(config.chatImageBridgeHolderClass, loader);
    Method bridgeGetter =
        Reflect.findMethodExact(bridgeHolder, config.chatImageBridgeGetterMethod);
    Object bridge = bridgeGetter.invoke(null);
    if (bridge == null) return null;

    return Reflect.findMethodExact(
            bridgeGetter.getReturnType(),
            config.chatImageRequestBuilderMethod,
            Context.class,
            copyInfoClass,
            boolean.class)
        .invoke(bridge, context, copyInfo, false);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isEmpty();
  }
}
