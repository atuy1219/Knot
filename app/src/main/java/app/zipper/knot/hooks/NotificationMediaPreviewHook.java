package app.zipper.knot.hooks;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Bundle;
import app.zipper.knot.Knot;
import app.zipper.knot.KnotConfig;
import app.zipper.knot.LineVersion;
import app.zipper.knot.LoadParam;
import app.zipper.knot.Main;
import app.zipper.knot.Reflect;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONException;
import org.json.JSONObject;

public class NotificationMediaPreviewHook implements BaseHook {
  static final String REPOST_MARKER = "knot.notification_media_preview_repost";

  private static final int ACTIVE_LOOKUP_ATTEMPTS = 4;
  private static final long ACTIVE_LOOKUP_DELAY_MS = 50L;
  private static final int LARGE_IMAGE_GROUP_MIN_SIZE = 5;
  private static final long LARGE_IMAGE_REPOST_DELAY_MS = 1_000L;
  private static final ExecutorService executor =
      Executors.newFixedThreadPool(
          4,
          runnable -> {
            Thread thread = new Thread(runnable, "Knot-NotificationMedia");
            thread.setDaemon(true);
            return thread;
          });
  private static final ScheduledExecutorService repostExecutor =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "Knot-NotificationMediaRepost");
            thread.setDaemon(true);
            return thread;
          });
  private static final Map<ImageGroupKey, PendingImageGroup> pendingImageGroups = new HashMap<>();

  @Override
  public void hook(KnotConfig config, LoadParam lpparam) throws Throwable {
    LineVersion.Config version = LineVersion.get();
    if (version == null || !hasText(version.notification.decryptedResultClass)) return;

    Knot.module
        .hook(
            Reflect.findMethodExact(
                NotificationManager.class, "notify", String.class, int.class, Notification.class))
        .intercept(
            chain -> {
              if (!config.notificationMediaPreview.enabled) return chain.proceed();

              String tag = (String) chain.getArg(0);
              int id = (int) chain.getArg(1);
              Notification notification = (Notification) chain.getArg(2);
              if (!isCandidate(tag, notification)) return chain.proceed();

              LineVersion.Config currentVersion = LineVersion.get();
              if (currentVersion == null) return chain.proceed();
              String messageId =
                  stringExtra(notification.extras, currentVersion.notification.messageIdExtra);
              if (!hasText(messageId)) return chain.proceed();

              NotificationMediaCaptureStore.MessageData captured =
                  NotificationMediaCaptureStore.take(messageId);
              Object result = chain.proceed();
              if (captured != null) {
                dispatchCaptured(tag, id, notification, messageId, captured);
                return result;
              }

              NotificationMediaCaptureStore.register(
                  messageId, () -> resumeAfterCapture(tag, id, notification, messageId));
              return result;
            });
  }

  private static void resumeAfterCapture(
      String tag, int id, Notification notification, String messageId) {
    NotificationMediaCaptureStore.MessageData captured =
        NotificationMediaCaptureStore.take(messageId);
    if (captured != null) dispatchCaptured(tag, id, notification, messageId, captured);
  }

  private static void dispatchCaptured(
      String tag,
      int id,
      Notification notification,
      String messageId,
      NotificationMediaCaptureStore.MessageData captured) {
    if (captured.isImage()) {
      ImageGroupMetadata group = ImageGroupMetadata.parse(captured.parameter);
      executor.execute(() -> updateImage(tag, id, notification, messageId, group));
      return;
    }

    if (captured.isSticker()) {
      LineStickerGlideMediaResolver.StickerMetadata sticker =
          LineStickerGlideMediaResolver.parse(captured.parameter);
      if (sticker != null) {
        executor.execute(() -> updateSticker(tag, id, notification, messageId, captured, sticker));
      }
      return;
    }

    if (captured.isSticon()) {
      executor.execute(() -> updateSticon(tag, id, notification, messageId, captured));
    }
  }

  private static void updateImage(
      String tag, int id, Notification original, String messageId, ImageGroupMetadata group) {
    Context context = Knot.currentApplication();
    if (context == null) return;
    NotificationMediaFileStore.Attachment attachment =
        LineChatGlideMediaResolver.acquire(context, messageId);
    if (attachment == null) return;

    if (group != null) {
      attachment =
          NotificationMediaFileStore.constrainForMessagingStyle(context, messageId, attachment);
      queueLargeImageGroup(context, tag, id, original, messageId, attachment, group);
      return;
    }

    postMedia(context, tag, id, original, messageId, attachment, true);
  }

  private static void updateSticker(
      String tag,
      int id,
      Notification original,
      String messageId,
      NotificationMediaCaptureStore.MessageData captured,
      LineStickerGlideMediaResolver.StickerMetadata sticker) {
    Context context = Knot.currentApplication();
    if (context == null) return;
    NotificationMediaFileStore.Attachment attachment =
        LineStickerGlideMediaResolver.acquire(context, sticker);
    if (attachment != null) postMedia(context, tag, id, original, messageId, attachment, false);
  }

  private static void updateSticon(
      String tag,
      int id,
      Notification original,
      String messageId,
      NotificationMediaCaptureStore.MessageData captured) {
    Context context = Knot.currentApplication();
    if (context == null) return;
    NotificationMediaFileStore.Attachment attachment =
        LineSticonMediaResolver.acquire(context, captured);
    if (attachment != null) postMedia(context, tag, id, original, messageId, attachment, true);
  }

  private static synchronized void postMedia(
      Context context,
      String tag,
      int id,
      Notification original,
      String messageId,
      NotificationMediaFileStore.Attachment attachment,
      boolean constrain) {
    try {
      if (constrain) {
        attachment =
            NotificationMediaFileStore.constrainForMessagingStyle(context, messageId, attachment);
      }
      List<PendingMedia> items = new ArrayList<>(1);
      items.add(new PendingMedia(original, messageId, attachment, 0));
      buildAndRepost(context, tag, id, items);
    } catch (Throwable t) {
      Knot.log("Knot: notification media preview failed: " + t.getClass().getSimpleName());
    }
  }

  private static synchronized void queueLargeImageGroup(
      Context context,
      String tag,
      int id,
      Notification original,
      String messageId,
      NotificationMediaFileStore.Attachment attachment,
      ImageGroupMetadata group) {
    ImageGroupKey key = new ImageGroupKey(tag, id, group.groupId);
    PendingImageGroup pending = pendingImageGroups.get(key);
    if (pending == null) {
      pending = new PendingImageGroup(context, tag, id);
      pendingImageGroups.put(key, pending);
    }

    pending.items.add(new PendingMedia(original, messageId, attachment, group.sequence));
    if (pending.future != null) pending.future.cancel(false);
    long generation = ++pending.generation;
    pending.future =
        repostExecutor.schedule(
            () -> flushLargeImageGroup(key, generation),
            LARGE_IMAGE_REPOST_DELAY_MS,
            TimeUnit.MILLISECONDS);
  }

  private static void flushLargeImageGroup(ImageGroupKey key, long generation) {
    synchronized (NotificationMediaPreviewHook.class) {
      PendingImageGroup pending = pendingImageGroups.get(key);
      if (pending == null || pending.generation != generation || pending.items.isEmpty()) return;
      pendingImageGroups.remove(key);
      pending.items.sort((left, right) -> Integer.compare(left.sequence, right.sequence));

      try {
        buildAndRepost(pending.context, pending.tag, pending.id, pending.items);
      } catch (Throwable t) {
        Knot.log("Knot: notification media preview failed: " + t.getClass().getSimpleName());
      }
    }
  }

  private static void buildAndRepost(
      Context context, String tag, int id, List<PendingMedia> items) {
    Notification active = awaitActiveNotification(context, tag, id);
    if (active == null || active.extras == null) return;

    boolean stackEnabled = Main.options.stackMessageNotifications.enabled;
    Notification enriched = active;
    boolean changed = false;
    if (stackEnabled) {
      for (PendingMedia item : items) {
        Notification built =
            StackMessageNotificationsHook.buildMediaMessageNotification(
                context,
                tag,
                id,
                enriched,
                item.original,
                item.messageId,
                item.attachment,
                true);
        if (built != null) {
          enriched = built;
          changed = true;
        }
      }
    } else {
      LineVersion.Config currentVersion = LineVersion.get();
      if (currentVersion == null) return;
      String activeMessageId =
          stringExtra(active.extras, currentVersion.notification.messageIdExtra);
      for (int i = items.size() - 1; i >= 0; i--) {
        PendingMedia item = items.get(i);
        if (!item.messageId.equals(activeMessageId)) continue;
        Notification built =
            StackMessageNotificationsHook.buildMediaMessageNotification(
                context,
                tag,
                id,
                active,
                item.original,
                item.messageId,
                item.attachment,
                false);
        if (built != null) {
          enriched = built;
          changed = true;
        }
        break;
      }
    }
    if (!changed) return;

    Notification.Builder builder = Notification.Builder.recoverBuilder(context, enriched);
    Bundle marker = new Bundle();
    marker.putBoolean(REPOST_MARKER, true);
    builder.addExtras(marker);
    builder.setOnlyAlertOnce(true);
    repost(context, tag, id, builder.build());
  }

  private static Notification awaitActiveNotification(Context context, String tag, int id) {
    for (int attempt = 0; attempt < ACTIVE_LOOKUP_ATTEMPTS; attempt++) {
      Notification active = StackMessageNotificationsHook.activeNotification(context, tag, id);
      if (active != null) return active;
      if (attempt + 1 >= ACTIVE_LOOKUP_ATTEMPTS) break;
      try {
        Thread.sleep(ACTIVE_LOOKUP_DELAY_MS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      }
    }
    return null;
  }

  private static boolean isCandidate(String tag, Notification notification) {
    if (notification == null || notification.extras == null) return false;
    if (notification.extras.getBoolean(REPOST_MARKER, false)) return false;
    if ((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0) return false;
    if ((notification.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) return false;
    if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return false;
    if (Notification.CATEGORY_CALL.equals(notification.category)
        || Notification.CATEGORY_SERVICE.equals(notification.category)) return false;

    LineVersion.Config version = LineVersion.get();
    if (version == null) return false;
    LineVersion.Config.Notification notificationConfig = version.notification;
    return sameNonEmpty(tag, notificationConfig.messageNotificationTag)
        || sameNonEmpty(tag, notificationConfig.chatNotificationTag);
  }

  private static void repost(Context context, String tag, int id, Notification notification) {
    NotificationManager manager =
        (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    if (manager == null) return;
    if (tag == null) manager.notify(id, notification);
    else manager.notify(tag, id, notification);
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

  private static final class ImageGroupMetadata {
    final String groupId;
    final int sequence;

    ImageGroupMetadata(String groupId, int sequence) {
      this.groupId = groupId;
      this.sequence = sequence;
    }

    static ImageGroupMetadata parse(String parameter) {
      if (!hasText(parameter)) return null;
      try {
        JSONObject json = new JSONObject(parameter);
        int total = intValue(json, "GTOTAL");
        String groupId = json.optString("GID", null);
        if (total < LARGE_IMAGE_GROUP_MIN_SIZE || !hasText(groupId)) return null;
        return new ImageGroupMetadata(groupId, intValue(json, "GSEQ"));
      } catch (JSONException ignored) {
        return null;
      }
    }

    private static int intValue(JSONObject json, String key) {
      String value = json.optString(key, null);
      if (!hasText(value)) return 0;
      try {
        return Integer.parseInt(value);
      } catch (NumberFormatException ignored) {
        return 0;
      }
    }
  }

  private static final class ImageGroupKey {
    final String tag;
    final int id;
    final String groupId;

    ImageGroupKey(String tag, int id, String groupId) {
      this.tag = tag;
      this.id = id;
      this.groupId = groupId;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) return true;
      if (!(obj instanceof ImageGroupKey)) return false;
      ImageGroupKey other = (ImageGroupKey) obj;
      return id == other.id
          && groupId.equals(other.groupId)
          && (tag == null ? other.tag == null : tag.equals(other.tag));
    }

    @Override
    public int hashCode() {
      int result = 31 * id + (tag == null ? 0 : tag.hashCode());
      return 31 * result + groupId.hashCode();
    }
  }

  private static final class PendingImageGroup {
    final Context context;
    final String tag;
    final int id;
    final List<PendingMedia> items = new ArrayList<>();
    long generation;
    ScheduledFuture<?> future;

    PendingImageGroup(Context context, String tag, int id) {
      this.context = context;
      this.tag = tag;
      this.id = id;
    }
  }

  private static final class PendingMedia {
    final Notification original;
    final String messageId;
    final NotificationMediaFileStore.Attachment attachment;
    final int sequence;

    PendingMedia(
        Notification original,
        String messageId,
        NotificationMediaFileStore.Attachment attachment,
        int sequence) {
      this.original = original;
      this.messageId = messageId;
      this.attachment = attachment;
      this.sequence = sequence;
    }
  }
}
