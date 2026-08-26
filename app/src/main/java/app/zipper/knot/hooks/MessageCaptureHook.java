package app.zipper.knot.hooks;

import android.content.ContentValues;
import app.zipper.knot.Knot;
import app.zipper.knot.KnotConfig;
import app.zipper.knot.LoadParam;
import app.zipper.knot.Reflect;
import java.util.Map;
import org.json.JSONObject;

/**
 * Captures LINE's normalized receive-message object after E2EE processing and before persistence.
 *
 * <p>LINE 26.13.0 routes RECEIVE_MESSAGE operations through te8.b3. Its f(...) method receives the
 * normalized Thrift Message (rg8.od) directly. Hooking this point avoids hooking SQLite entirely and
 * lets the notification fast path consume the same message object LINE itself is about to persist.
 * Database reads remain only as compatibility fallbacks in the notification hooks.
 */
public class MessageCaptureHook implements BaseHook {
  private static final String RECEIVE_MESSAGE_HANDLER = "te8.b3";
  private static final String RECEIVE_MESSAGE_METHOD = "f";
  private static final String MESSAGE_CLASS = "rg8.od";

  private static final int MESSAGE_TYPE_MESSAGE = 1;
  private static final int ATTACHMENT_NONE = 0;
  private static final int ATTACHMENT_IMAGE = 1;
  private static final int ATTACHMENT_OTHER = 2;

  @Override
  public void hook(KnotConfig config, LoadParam lpparam) throws Throwable {
    if (!config.imageNotificationPreview.enabled) return;

    Class<?> handlerClass = Reflect.findClass(RECEIVE_MESSAGE_HANDLER, lpparam.classLoader);
    Class<?> messageClass = Reflect.findClass(MESSAGE_CLASS, lpparam.classLoader);

    Knot.hookAll(
        handlerClass,
        RECEIVE_MESSAGE_METHOD,
        chain -> {
          try {
            Object message = chain.getArg(0);
            if (message != null && messageClass.isInstance(message)) {
              captureMessage(message);
            }
          } catch (Throwable t) {
            Knot.log("Knot: message object capture failed: " + t.getClass().getSimpleName());
          }
          return chain.proceed();
        });

    Knot.log(
        "Knot: message object capture installed: "
            + RECEIVE_MESSAGE_HANDLER
            + "#"
            + RECEIVE_MESSAGE_METHOD);
  }

  private static void captureMessage(Object message) {
    String from = stringField(message, "a");
    String to = stringField(message, "b");
    Object toType = objectField(message, "c");
    String serverId = stringField(message, "d");
    long createdTime = longField(message, "e");
    String text = stringField(message, "g");
    Object contentType = objectField(message, "j");
    Object metadataValue = objectField(message, "k");

    if (!hasText(serverId)) return;

    String contentTypeName = contentType == null ? null : contentType.toString();
    ContentValues values = new ContentValues();
    values.put("server_id", serverId);
    if (hasText(from)) values.put("from_mid", from);
    if (hasText(text)) values.put("content", text);
    if (createdTime > 0L) values.put("created_time", createdTime);
    values.put("type", MESSAGE_TYPE_MESSAGE);

    Integer attachmentType = attachmentType(contentTypeName);
    if (attachmentType != null) values.put("attachement_type", attachmentType);

    String chatId = resolveChatId(from, to, toType);
    if (hasText(chatId)) values.put("chat_id", chatId);

    String parameter = metadataJson(metadataValue);
    if (hasText(parameter)) values.put("parameter", parameter);

    CapturedMessageStore.MessageData captured =
        CapturedMessageStore.capture("chat_history", values, serverId);
    if (captured != null) {
      Knot.log(
          "Knot: message object captured type="
              + (hasText(contentTypeName) ? contentTypeName : "unknown")
              + " text="
              + (hasText(text) ? "present" : "absent")
              + " metadata="
              + (hasText(parameter) ? "present" : "absent"));
    }
  }

  private static Integer attachmentType(String contentTypeName) {
    if (!hasText(contentTypeName)) return null;
    if ("NONE".equals(contentTypeName)) return ATTACHMENT_NONE;
    if ("IMAGE".equals(contentTypeName)) return ATTACHMENT_IMAGE;
    return ATTACHMENT_OTHER;
  }

  private static String resolveChatId(String from, String to, Object toType) {
    if (toType != null && "USER".equals(toType.toString())) return from;
    return hasText(to) ? to : from;
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

  private static long longField(Object object, String name) {
    try {
      return Reflect.getLongField(object, name);
    } catch (Throwable ignored) {
      return 0L;
    }
  }

  private static boolean hasText(String value) {
    return value != null && !value.isEmpty();
  }
}
