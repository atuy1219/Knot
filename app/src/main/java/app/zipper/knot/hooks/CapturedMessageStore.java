package app.zipper.knot.hooks;

import android.content.ContentValues;
import android.os.SystemClock;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small in-memory cache for message data observed before LINE persists chat_history rows. */
final class CapturedMessageStore {
  static final int MESSAGE_TYPE_MESSAGE = 1;
  static final int ATTACHMENT_NONE = 0;
  static final int ATTACHMENT_IMAGE = 1;

  private static final int MAX_MESSAGES = 128;
  private static final int MAX_CONTACTS = 256;
  private static final long MESSAGE_TTL_MS = 5 * 60 * 1000L;

  private static final Object lock = new Object();
  private static final LinkedHashMap<String, MessageData> messages =
      new LinkedHashMap<String, MessageData>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, MessageData> eldest) {
          return size() > MAX_MESSAGES;
        }
      };
  private static final LinkedHashMap<String, String> contacts =
      new LinkedHashMap<String, String>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
          return size() > MAX_CONTACTS;
        }
      };

  private CapturedMessageStore() {}

  static MessageData capture(String table, ContentValues values, String forcedServerId) {
    if (table == null || values == null) return null;
    if ("contacts".equalsIgnoreCase(table)) {
      captureContact(values);
      return null;
    }
    if (!"chat_history".equalsIgnoreCase(table)) return null;

    String serverId = firstString(values, "server_id", "serverId");
    if (!hasText(serverId)) serverId = forcedServerId;
    if (!hasText(serverId)) return null;

    synchronized (lock) {
      cleanupExpiredLocked();
      MessageData previous = messages.get(serverId);
      MessageData next = merge(serverId, previous, values);
      messages.put(serverId, next);
      return next;
    }
  }

  static MessageData get(String serverId) {
    if (!hasText(serverId)) return null;
    synchronized (lock) {
      MessageData data = messages.get(serverId);
      if (data == null) return null;
      if (ageMs(data) > MESSAGE_TTL_MS) {
        messages.remove(serverId);
        return null;
      }
      return data;
    }
  }

  static String senderName(String mid) {
    if (!hasText(mid)) return null;
    synchronized (lock) {
      return contacts.get(mid);
    }
  }

  static long ageMs(MessageData data) {
    if (data == null || data.capturedAtElapsedNanos <= 0L) return -1L;
    long delta = SystemClock.elapsedRealtimeNanos() - data.capturedAtElapsedNanos;
    return Math.max(0L, delta / 1_000_000L);
  }

  private static MessageData merge(String serverId, MessageData previous, ContentValues values) {
    String localId = choose(firstString(values, "id"), previous == null ? null : previous.localId);
    String chatId = choose(firstString(values, "chat_id"), previous == null ? null : previous.chatId);
    String fromMid = choose(firstString(values, "from_mid"), previous == null ? null : previous.fromMid);
    String content = choose(firstString(values, "content"), previous == null ? null : previous.content);
    String localUri =
        choose(
            firstString(values, "attachement_local_uri", "attachment_local_uri"),
            previous == null ? null : previous.localUri);
    String parameter =
        choose(firstString(values, "parameter"), previous == null ? null : previous.parameter);

    int messageType =
        chooseInt(
            firstInt(values, "type"),
            previous == null ? Integer.MIN_VALUE : previous.messageType);
    int attachmentType =
        chooseInt(
            firstInt(values, "attachement_type", "attachment_type"),
            previous == null ? Integer.MIN_VALUE : previous.attachmentType);
    long createdTime =
        chooseLong(
            firstLong(values, "created_time"), previous == null ? 0L : previous.createdTime);

    return new MessageData(
        serverId,
        localId,
        chatId,
        fromMid,
        content,
        messageType,
        attachmentType,
        localUri,
        parameter,
        createdTime,
        SystemClock.elapsedRealtimeNanos());
  }

  private static void captureContact(ContentValues values) {
    String mid = firstString(values, "m_id", "mid", "id");
    String name =
        firstString(values, "name", "display_name", "server_name", "profile_name", "nickname");
    if (!hasText(mid) || !hasText(name)) return;
    synchronized (lock) {
      contacts.put(mid, name);
    }
  }

  private static void cleanupExpiredLocked() {
    java.util.Iterator<Map.Entry<String, MessageData>> iterator = messages.entrySet().iterator();
    while (iterator.hasNext()) {
      if (ageMs(iterator.next().getValue()) > MESSAGE_TTL_MS) iterator.remove();
    }
  }

  private static String firstString(ContentValues values, String... keys) {
    for (String key : keys) {
      if (!values.containsKey(key)) continue;
      try {
        String value = values.getAsString(key);
        if (hasText(value)) return value;
      } catch (Throwable ignored) {
      }
    }
    return null;
  }

  private static Integer firstInt(ContentValues values, String... keys) {
    for (String key : keys) {
      if (!values.containsKey(key)) continue;
      try {
        Integer value = values.getAsInteger(key);
        if (value != null) return value;
      } catch (Throwable ignored) {
      }
    }
    return null;
  }

  private static Long firstLong(ContentValues values, String... keys) {
    for (String key : keys) {
      if (!values.containsKey(key)) continue;
      try {
        Long value = values.getAsLong(key);
        if (value != null) return value;
      } catch (Throwable ignored) {
      }
    }
    return null;
  }

  private static String choose(String value, String fallback) {
    return hasText(value) ? value : fallback;
  }

  private static int chooseInt(Integer value, int fallback) {
    return value == null ? fallback : value;
  }

  private static long chooseLong(Long value, long fallback) {
    return value == null ? fallback : value;
  }

  private static boolean hasText(String value) {
    return value != null && !value.isEmpty();
  }

  static final class MessageData {
    final String serverId;
    final String localId;
    final String chatId;
    final String fromMid;
    final String content;
    final int messageType;
    final int attachmentType;
    final String localUri;
    final String parameter;
    final long createdTime;
    final long capturedAtElapsedNanos;

    MessageData(
        String serverId,
        String localId,
        String chatId,
        String fromMid,
        String content,
        int messageType,
        int attachmentType,
        String localUri,
        String parameter,
        long createdTime,
        long capturedAtElapsedNanos) {
      this.serverId = serverId;
      this.localId = localId;
      this.chatId = chatId;
      this.fromMid = fromMid;
      this.content = content;
      this.messageType = messageType;
      this.attachmentType = attachmentType;
      this.localUri = localUri;
      this.parameter = parameter;
      this.createdTime = createdTime;
      this.capturedAtElapsedNanos = capturedAtElapsedNanos;
    }

    boolean isPlainText() {
      if (!hasText(content)) return false;
      if (messageType != Integer.MIN_VALUE && messageType != MESSAGE_TYPE_MESSAGE) return false;
      return attachmentType == Integer.MIN_VALUE || attachmentType == ATTACHMENT_NONE;
    }

    boolean isImage() {
      return attachmentType == ATTACHMENT_IMAGE;
    }
  }
}
