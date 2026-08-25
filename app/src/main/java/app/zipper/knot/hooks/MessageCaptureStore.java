package app.zipper.knot.hooks;

import android.content.ContentValues;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small in-memory cache for decrypted/persisted LINE messages captured before notification. */
final class MessageCaptureStore {
  static final int UNKNOWN_INT = Integer.MIN_VALUE;
  private static final int MAX_ENTRIES = 128;
  private static final long TTL_MS = 2 * 60 * 1000L;

  private static final LinkedHashMap<String, MessageInfo> messages =
      new LinkedHashMap<String, MessageInfo>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, MessageInfo> eldest) {
          return size() > MAX_ENTRIES;
        }
      };

  private MessageCaptureStore() {}

  static synchronized void put(MessageInfo info) {
    if (info == null || !hasText(info.messageId)) return;
    info.capturedAtMs = System.currentTimeMillis();
    MessageInfo previous = messages.get(info.messageId);
    messages.put(info.messageId, previous == null ? info : merge(previous, info));
    pruneExpiredLocked();
  }

  static synchronized MessageInfo get(String messageId) {
    if (!hasText(messageId)) return null;
    MessageInfo info = messages.get(messageId);
    if (info == null) return null;
    if (System.currentTimeMillis() - info.capturedAtMs > TTL_MS) {
      messages.remove(messageId);
      return null;
    }
    return info.copy();
  }

  static MessageInfo fromChatHistoryValues(ContentValues values) {
    if (values == null) return null;
    MessageInfo info = new MessageInfo();
    info.messageId = firstString(values, "server_id", "message_id");
    if (!hasText(info.messageId)) return null;
    info.localId = firstString(values, "id", "_id");
    info.chatId = firstString(values, "chat_id");
    info.fromMid = firstString(values, "from_mid", "from");
    info.content = firstString(values, "content", "text");
    info.parameter = firstString(values, "parameter", "content_metadata");
    info.localUri = firstString(values, "attachement_local_uri", "attachment_local_uri");
    info.messageType = firstInt(values, "type", "message_type");
    info.attachmentType = firstInt(values, "attachement_type", "attachment_type");
    info.createdTime = firstLong(values, "created_time", "createdTime", "timestamp");
    return info;
  }

  private static MessageInfo merge(MessageInfo old, MessageInfo next) {
    MessageInfo out = old.copy();
    if (hasText(next.localId)) out.localId = next.localId;
    if (hasText(next.chatId)) out.chatId = next.chatId;
    if (hasText(next.fromMid)) out.fromMid = next.fromMid;
    if (hasText(next.senderName)) out.senderName = next.senderName;
    if (hasText(next.content)) out.content = next.content;
    if (hasText(next.parameter)) out.parameter = next.parameter;
    if (hasText(next.localUri)) out.localUri = next.localUri;
    if (next.messageType != UNKNOWN_INT) out.messageType = next.messageType;
    if (next.attachmentType != UNKNOWN_INT) out.attachmentType = next.attachmentType;
    if (next.createdTime > 0L) out.createdTime = next.createdTime;
    out.capturedAtMs = Math.max(old.capturedAtMs, next.capturedAtMs);
    return out;
  }

  private static void pruneExpiredLocked() {
    long cutoff = System.currentTimeMillis() - TTL_MS;
    java.util.Iterator<Map.Entry<String, MessageInfo>> it = messages.entrySet().iterator();
    while (it.hasNext()) {
      if (it.next().getValue().capturedAtMs < cutoff) it.remove();
    }
  }

  private static String firstString(ContentValues values, String... keys) {
    for (String key : keys) {
      Object value = values.get(key);
      if (value != null) return String.valueOf(value);
    }
    return null;
  }

  private static int firstInt(ContentValues values, String... keys) {
    for (String key : keys) {
      try {
        Integer value = values.getAsInteger(key);
        if (value != null) return value;
      } catch (Throwable ignored) {
      }
    }
    return UNKNOWN_INT;
  }

  private static long firstLong(ContentValues values, String... keys) {
    for (String key : keys) {
      try {
        Long value = values.getAsLong(key);
        if (value != null) return value;
      } catch (Throwable ignored) {
      }
    }
    return 0L;
  }

  private static boolean hasText(String value) {
    return value != null && !value.isEmpty();
  }

  static final class MessageInfo {
    String messageId;
    String localId;
    String chatId;
    String fromMid;
    String senderName;
    String content;
    String parameter;
    String localUri;
    int messageType = UNKNOWN_INT;
    int attachmentType = UNKNOWN_INT;
    long createdTime;
    long capturedAtMs;

    MessageInfo copy() {
      MessageInfo out = new MessageInfo();
      out.messageId = messageId;
      out.localId = localId;
      out.chatId = chatId;
      out.fromMid = fromMid;
      out.senderName = senderName;
      out.content = content;
      out.parameter = parameter;
      out.localUri = localUri;
      out.messageType = messageType;
      out.attachmentType = attachmentType;
      out.createdTime = createdTime;
      out.capturedAtMs = capturedAtMs;
      return out;
    }
  }
}
