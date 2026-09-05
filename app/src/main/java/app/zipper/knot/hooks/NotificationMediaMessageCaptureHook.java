package app.zipper.knot.hooks;

import app.zipper.knot.Knot;
import app.zipper.knot.KnotConfig;
import app.zipper.knot.LineVersion;
import app.zipper.knot.LoadParam;
import app.zipper.knot.Reflect;
import java.lang.reflect.Constructor;
import java.util.Map;
import org.json.JSONObject;

public class NotificationMediaMessageCaptureHook implements BaseHook {
  @Override
  public void hook(KnotConfig config, LoadParam lpparam) throws Throwable {
    LineVersion.Config version = LineVersion.get();
    if (version == null) return;
    LineVersion.Config.Notification mediaConfig = version.notification;
    if (!hasText(mediaConfig.decryptedResultClass) || !hasText(mediaConfig.messageClass)) return;

    Class<?> decryptedResultClass =
        Reflect.findClass(mediaConfig.decryptedResultClass, lpparam.classLoader);
    Class<?> messageClass = Reflect.findClass(mediaConfig.messageClass, lpparam.classLoader);
    Constructor<?> decryptedConstructor =
        Reflect.findConstructorExact(decryptedResultClass, messageClass);

    Knot.module
        .hook(decryptedConstructor)
        .intercept(
            chain -> {
              try {
                Object message = chain.getArg(0);
                if (message != null && messageClass.isInstance(message)) {
                  captureMessage(message, mediaConfig);
                }
              } catch (Throwable t) {
                Knot.log(
                    "Knot: notification media capture failed: " + t.getClass().getSimpleName());
              }
              return chain.proceed();
            });
  }

  private static void captureMessage(Object message, LineVersion.Config.Notification config) {
    String messageId = stringField(message, config.messageServerIdField);
    if (!hasText(messageId)) return;

    Object contentType = objectField(message, config.messageContentTypeField);
    String contentTypeName = contentType == null ? null : contentType.toString();

    int type;
    String text = null;
    String parameter;
    if ("IMAGE".equals(contentTypeName)) {
      type = NotificationMediaCaptureStore.TYPE_IMAGE;
      parameter =
          metadataJson(
              objectField(message, config.messageMetadataField), "GTOTAL", "GID", "GSEQ");
    } else if ("STICKER".equals(contentTypeName)) {
      type = NotificationMediaCaptureStore.TYPE_STICKER;
      parameter = metadataJson(objectField(message, config.messageMetadataField), "CSSTKID");
    } else if ("NONE".equals(contentTypeName)) {
      parameter = metadataJson(objectField(message, config.messageMetadataField), "REPLACE");
      if (!hasText(parameter)) return;
      text = stringField(message, config.messageTextField);
      if (!hasText(text)) return;
      type = NotificationMediaCaptureStore.TYPE_STICON;
    } else {
      return;
    }

    NotificationMediaCaptureStore.capture(messageId, type, text, parameter);
  }

  private static String metadataJson(Object value, String... keys) {
    if (!(value instanceof Map) || keys == null || keys.length == 0) return null;
    Map<?, ?> metadata = (Map<?, ?>) value;
    try {
      JSONObject json = new JSONObject();
      for (String key : keys) {
        if (!hasText(key)) continue;
        Object metadataValue = metadata.get(key);
        if (metadataValue == null) continue;
        String text = String.valueOf(metadataValue);
        if (hasText(text)) json.put(key, text);
      }
      return json.length() == 0 ? null : json.toString();
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static Object objectField(Object object, String name) {
    if (!hasText(name)) return null;
    try {
      return Reflect.getObjectField(object, name);
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static String stringField(Object object, String name) {
    Object value = objectField(object, name);
    return value == null ? null : String.valueOf(value);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isEmpty();
  }
}
