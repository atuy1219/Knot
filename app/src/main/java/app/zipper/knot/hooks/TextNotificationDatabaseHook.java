package app.zipper.knot.hooks;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Person;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Parcelable;
import app.zipper.knot.Knot;
import app.zipper.knot.KnotConfig;
import app.zipper.knot.LineVersion;
import app.zipper.knot.LoadParam;
import app.zipper.knot.Reflect;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Rebuilds plain-text LINE message notifications from decoded message data.
 *
 * <p>The fast path uses message fields captured immediately before LINE persists chat_history, so
 * notification construction can complete synchronously without opening SQLite. If that capture is
 * unavailable, the original notification is posted first and the existing database lookup remains
 * as a compatibility fallback.
 */
public class TextNotificationDatabaseHook implements BaseHook {
  private static final String REPOST_MARKER = "knot.text_notification_db_repost";
  private static final String DB_MESSAGE_TYPE = "knot.db_message_type";
  private static final String DB_ATTACHMENT_TYPE = "knot.db_attachment_type";
  private static final String DB_SENDER_MID = "knot.db_sender_mid";

  private static final int MESSAGE_TYPE_MESSAGE = 1;
  private static final int ATTACHMENT_NONE = 0;
  private static final int DB_ATTEMPTS = 30;
  private static final long RETRY_DELAY_MS = 50L;

  private static final ExecutorService executor =
      Executors.newFixedThreadPool(
          2,
          runnable -> {
            Thread thread = new Thread(runnable, "Knot-TextNotification");
            thread.setDaemon(true);
            return thread;
          });

  private static final ThreadLocal<Boolean> reposting =
      ThreadLocal.withInitial(() -> Boolean.FALSE);

  @Override
  public void hook(KnotConfig config, LoadParam lpparam) throws Throwable {
    Knot.module
        .hook(
            Reflect.findMethodExact(
                NotificationManager.class, "notify", String.class, int.class, Notification.class))
        .intercept(
            chain -> {
              if (!config.imageNotificationPreview.enabled || Boolean.TRUE.equals(reposting.get())) {
                return chain.proceed();
              }

              String tag = (String) chain.getArg(0);
              int id = (int) chain.getArg(1);
              Notification notification = (Notification) chain.getArg(2);
              if (!isCandidate(tag, notification)) return chain.proceed();

              LineVersion.Config version = LineVersion.get();
              if (version == null) return chain.proceed();
              String messageId =
                  stringExtra(notification.extras, version.notification.messageIdExtra);
              if (!hasText(messageId)) return chain.proceed();

              CapturedMessageStore.MessageData captured = CapturedMessageStore.get(messageId);
              if (captured != null && captured.isPlainText()) {
                Context context = Knot.currentApplication();
                if (context != null) {
                  MessageRow row = fromCaptured(captured);
                  String sender = CapturedMessageStore.senderName(row.fromMid);
                  if (!hasText(sender)) sender = originalSender(notification.extras);
                  Notification enriched = rebuild(context, notification, row, sender);
                  if (enriched != null) {
                    Knot.log(
                        "Knot: text notification: pre-notify cache hit ageMs="
                            + CapturedMessageStore.ageMs(captured));
                    return chain.proceed(new Object[] {tag, id, enriched});
                  }
                }
              }

              // If the pre-persistence capture already proves this is media/system content, avoid a
              // redundant database lookup and let the appropriate notification hook handle it.
              if (definitelyNotPlainText(captured)) return chain.proceed();

              Object result = chain.proceed();
              executor.execute(() -> updateTextNotification(tag, id, notification, messageId));
              return result;
            });
  }

  private static boolean isCandidate(String tag, Notification notification) {
    if (notification == null || notification.extras == null) return false;
    if (notification.extras.getBoolean(REPOST_MARKER, false)) return false;
    if (notification.extras.getBoolean(ImageNotificationPreviewHook.REPOST_MARKER, false)) {
      return false;
    }
    if ((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0) return false;
    if ((notification.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) return false;
    if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return false;
    if (Notification.CATEGORY_CALL.equals(notification.category)
        || Notification.CATEGORY_SERVICE.equals(notification.category)) return false;

    LineVersion.Config version = LineVersion.get();
    if (version == null) return false;
    LineVersion.Config.Notification cfg = version.notification;
    return sameNonEmpty(tag, cfg.messageNotificationTag) || sameNonEmpty(tag, cfg.chatNotificationTag);
  }

  private static void updateTextNotification(
      String tag, int id, Notification original, String messageId) {
    try {
      Context context = Knot.currentApplication();
      if (context == null) return;

      MessageRow row = awaitMessageRow(context, messageId);
      if (!isPlainTextRow(row)) return;

      String sender = row.senderName;
      if (!hasText(sender)) sender = originalSender(original.extras);

      Notification enriched = rebuild(context, original, row, sender);
      if (enriched == null) return;
      repost(context, tag, id, enriched);

      Knot.log(
          "Knot: text notification: rebuilt from DB type="
              + row.messageType
              + " attachment="
              + row.attachmentType
              + " sender="
              + (hasText(sender) ? "resolved" : "unresolved"));
    } catch (Throwable t) {
      Knot.log("Knot: text notification enrichment failed: " + t);
    }
  }

  private static MessageRow fromCaptured(CapturedMessageStore.MessageData captured) {
    MessageRow row = new MessageRow();
    row.localId = captured.localId;
    row.chatId = captured.chatId;
    row.fromMid = captured.fromMid;
    row.content = captured.content;
    row.messageType = captured.messageType;
    row.attachmentType = captured.attachmentType;
    row.createdTime = captured.createdTime;
    row.senderName = CapturedMessageStore.senderName(captured.fromMid);
    return row;
  }

  private static boolean definitelyNotPlainText(CapturedMessageStore.MessageData captured) {
    if (captured == null) return false;
    if (captured.attachmentType != Integer.MIN_VALUE
        && captured.attachmentType != ATTACHMENT_NONE) return true;
    return captured.messageType != Integer.MIN_VALUE
        && captured.messageType != MESSAGE_TYPE_MESSAGE;
  }

  private static MessageRow awaitMessageRow(Context context, String messageId) {
    MessageRow last = null;
    for (int attempt = 0; attempt < DB_ATTEMPTS; attempt++) {
      MessageRow row = readMessageRow(context, messageId);
      if (row != null) {
        last = row;
        // Once LINE has persisted content and type information, waiting longer cannot improve a
        // normal text row. Media rows also return immediately so this hook does not poll for them.
        if (hasText(row.content)
            || row.attachmentType != Integer.MIN_VALUE
            || row.messageType != Integer.MIN_VALUE) {
          return row;
        }
      }
      if (!sleepBriefly()) break;
    }
    return last;
  }

  private static MessageRow readMessageRow(Context context, String messageId) {
    File dbFile = context.getDatabasePath("naver_line");
    if (!dbFile.exists()) return null;

    SQLiteDatabase db = null;
    Cursor cursor = null;
    try {
      db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
      cursor =
          db.rawQuery(
              "SELECT * FROM chat_history WHERE server_id = ? LIMIT 1",
              new String[] {messageId});
      if (!cursor.moveToFirst()) return null;

      MessageRow row = new MessageRow();
      row.localId = getString(cursor, "id");
      row.chatId = getString(cursor, "chat_id");
      row.fromMid = getString(cursor, "from_mid");
      row.content = getString(cursor, "content");
      row.messageType = getInt(cursor, "type", Integer.MIN_VALUE);
      row.attachmentType = getInt(cursor, "attachement_type", Integer.MIN_VALUE);
      row.createdTime = getLong(cursor, "created_time", 0L);
      row.senderName = resolveSenderName(db, row.fromMid);
      return row;
    } catch (Throwable ignored) {
      return null;
    } finally {
      if (cursor != null) cursor.close();
      if (db != null) db.close();
    }
  }

  private static String resolveSenderName(SQLiteDatabase db, String mid) {
    if (db == null || !hasText(mid)) return null;

    String[] midColumns = {"m_id", "mid", "id"};
    String[] nameColumns = {"name", "display_name", "profile_name", "nickname"};
    for (String midColumn : midColumns) {
      for (String nameColumn : nameColumns) {
        Cursor cursor = null;
        try {
          cursor =
              db.rawQuery(
                  "SELECT "
                      + quoteIdentifier(nameColumn)
                      + " FROM contacts WHERE "
                      + quoteIdentifier(midColumn)
                      + " = ? LIMIT 1",
                  new String[] {mid});
          if (cursor.moveToFirst() && !cursor.isNull(0)) {
            String value = cursor.getString(0);
            if (hasText(value)) return value;
          }
        } catch (Throwable ignored) {
          // contacts schema varies between LINE releases; try the next known column pair.
        } finally {
          if (cursor != null) cursor.close();
        }
      }
    }
    return null;
  }

  private static boolean isPlainTextRow(MessageRow row) {
    if (row == null || !hasText(row.content)) return false;
    if (row.messageType != Integer.MIN_VALUE && row.messageType != MESSAGE_TYPE_MESSAGE) {
      return false;
    }
    return row.attachmentType == Integer.MIN_VALUE || row.attachmentType == ATTACHMENT_NONE;
  }

  private static Notification rebuild(
      Context context, Notification original, MessageRow row, String sender) {
    try {
      Notification.Builder builder = Notification.Builder.recoverBuilder(context, original);
      Bundle metadata = new Bundle();
      metadata.putBoolean(REPOST_MARKER, true);
      // Also suppress the image hook when this enriched notification re-enters notify().
      metadata.putBoolean(ImageNotificationPreviewHook.REPOST_MARKER, true);
      if (row.messageType != Integer.MIN_VALUE) {
        metadata.putInt(DB_MESSAGE_TYPE, row.messageType);
      }
      if (row.attachmentType != Integer.MIN_VALUE) {
        metadata.putInt(DB_ATTACHMENT_TYPE, row.attachmentType);
      }
      if (hasText(row.fromMid)) metadata.putString(DB_SENDER_MID, row.fromMid);

      builder.addExtras(metadata);
      builder.setOnlyAlertOnce(true);
      builder.setContentText(row.content);

      if (hasText(sender)) {
        Notification.MessagingStyle style = new Notification.MessagingStyle("");
        CharSequence conversationTitle = conversationTitle(original.extras);
        if (hasText(conversationTitle)) style.setConversationTitle(conversationTitle);
        long timestamp = row.createdTime > 0L ? row.createdTime : System.currentTimeMillis();
        style.addMessage(row.content, timestamp, sender);
        builder.setStyle(style);
      }

      return builder.build();
    } catch (Throwable t) {
      Knot.log("Knot: text notification rebuild failed: " + t);
      return null;
    }
  }

  private static void repost(Context context, String tag, int id, Notification notification) {
    NotificationManager nm =
        (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    if (nm == null) return;

    reposting.set(Boolean.TRUE);
    try {
      if (tag == null) {
        nm.notify(id, notification);
      } else {
        nm.notify(tag, id, notification);
      }
    } finally {
      reposting.remove();
    }
  }

  private static CharSequence conversationTitle(Bundle extras) {
    if (extras == null) return null;
    CharSequence explicit = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
    if (hasText(explicit)) return explicit;
    if (extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)) {
      return extras.getCharSequence(Notification.EXTRA_TITLE);
    }
    return null;
  }

  private static String originalSender(Bundle extras) {
    if (extras == null) return null;
    Parcelable[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
    if (messages != null) {
      for (int i = messages.length - 1; i >= 0; i--) {
        if (!(messages[i] instanceof Bundle)) continue;
        Bundle message = (Bundle) messages[i];
        Parcelable personValue = message.getParcelable("sender_person");
        if (personValue instanceof Person) {
          CharSequence name = ((Person) personValue).getName();
          if (hasText(name)) return name.toString();
        }
        CharSequence sender = message.getCharSequence("sender");
        if (hasText(sender)) return sender.toString();
      }
    }
    return null;
  }

  private static String quoteIdentifier(String value) {
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }

  private static String getString(Cursor cursor, String column) {
    int index = cursor.getColumnIndex(column);
    if (index < 0 || cursor.isNull(index)) return null;
    return cursor.getString(index);
  }

  private static int getInt(Cursor cursor, String column, int fallback) {
    int index = cursor.getColumnIndex(column);
    if (index < 0 || cursor.isNull(index)) return fallback;
    return cursor.getInt(index);
  }

  private static long getLong(Cursor cursor, String column, long fallback) {
    int index = cursor.getColumnIndex(column);
    if (index < 0 || cursor.isNull(index)) return fallback;
    try {
      return cursor.getLong(index);
    } catch (Throwable ignored) {
      try {
        return Long.parseLong(cursor.getString(index));
      } catch (Throwable ignoredAgain) {
        return fallback;
      }
    }
  }

  private static String stringExtra(Bundle extras, String key) {
    if (extras == null || key == null) return null;
    Object value = extras.get(key);
    return value == null ? null : String.valueOf(value);
  }

  private static boolean sameNonEmpty(String a, String b) {
    return hasText(a) && hasText(b) && a.equals(b);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isEmpty();
  }

  private static boolean hasText(CharSequence value) {
    return value != null && value.length() > 0;
  }

  private static boolean sleepBriefly() {
    try {
      Thread.sleep(RETRY_DELAY_MS);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static final class MessageRow {
    String localId;
    String chatId;
    String fromMid;
    String senderName;
    String content;
    int messageType = Integer.MIN_VALUE;
    int attachmentType = Integer.MIN_VALUE;
    long createdTime;
  }
}
