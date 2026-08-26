package app.zipper.knot.hooks;

import android.content.ContentValues;
import app.zipper.knot.Knot;
import app.zipper.knot.KnotConfig;
import app.zipper.knot.LoadParam;
import app.zipper.knot.Reflect;
import java.lang.reflect.Constructor;
import java.util.Map;
import org.json.JSONObject;

/**
 * Captures LINE's successfully decrypted receive-message object before persistence.
 *
 * <p>LINE 26.13.0 wraps a successfully decrypted rg8.od Message in te8.b3$b$a (the Decrypted
 * result) while failures use a separate result class. Hooking only the Decrypted(Message)
 * constructor means Knot sees the normalized plaintext Message directly without intercepting
 * SQLite writes or failed-decryption traffic. Database reads remain only as compatibility
 * fallbacks in the notification hooks.
 */
public class MessageCaptureHook implements BaseHook {
  private static final String DECRYPTED_RESULT_CLASS = "te8.b3$b$a";
  private static final String MESSAGE_CLASS = "rg8.od";

  private static final int MESSAGE_TYPE_MESSAGE = 1;
  private static final int ATTACHMENT_NONE = 0;
  private static final int ATTACHMENT_IMAGE = 1;
  private static final int ATTACHMENT_OTHER = 2;

  @Override
  public void hook(KnotConfig config, LoadParam lpparam) throws Throwable {
    if (!config.imageNotificationPreview.enabled) return;

    Class<?> decryptedResultClass =
        Reflect.findClass(DECRYPTED_RESULT_CLASS, lpparam.classLoader);
    Class<?> messageClass = Reflect.findClass(MESSAGE_CLASS, lpparam.classLoader);
    Constructor<?> decryptedConstructor =
        Reflect.findConstructorExact(decryptedResultClass, messageClass);

    Knot.module
        .hook(decryptedConstructor)
        .intercept(
            chain -> {
              try {
                Object message = chain.getArg(0);
                if (message != null && messageClass.isInstance(message)) {
                  captureMessage(message);
                }
              } catch (Throwable t) {
                Knot.log("Knot: decrypted message capture failed: " + t.getClass().getSimpleName());
              }
              return chain.proceed();
            });

    Knot.log("Knot: decrypted Message capture installed");
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
          "Knot: decrypted Message captured type="
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
