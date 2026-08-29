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
    Object metadata = objectField(message, config.messageMetadataField);
    String parameter = metadataJson(metadata);

    int type;
    String text = null;
    Object retainedMessage = null;
    if ("IMAGE".equals(contentTypeName)) {
      type = NotificationMediaCaptureStore.TYPE_IMAGE;
    } else if ("STICKER".equals(contentTypeName)) {
      type = NotificationMediaCaptureStore.TYPE_STICKER;
      retainedMessage = message;
    } else if ("NONE".equals(contentTypeName) && hasMetadataValue(metadata, "REPLACE")) {
      text = stringField(message, config.messageTextField);
      if (!hasText(text) || !hasText(parameter)) return;
      type = NotificationMediaCaptureStore.TYPE_STICON;
    } else {
      return;
    }

    NotificationMediaCaptureStore.capture(messageId, type, text, parameter, retainedMessage);
  }

  private static boolean hasMetadataValue(Object value, String key) {
    if (!(value instanceof Map) || !hasText(key)) return false;
    Object metadataValue = ((Map<?, ?>) value).get(key);
    return metadataValue != null && hasText(String.valueOf(metadataValue));
  }

  private static String metadataJson(Object value) {
    if (!(value instanceof Map)) return null;
    try {
      JSONObject json = new JSONObject();
      for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) continue;
        json.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
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
