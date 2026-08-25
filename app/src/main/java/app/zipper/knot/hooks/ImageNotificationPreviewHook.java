package app.zipper.knot.hooks;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import app.zipper.knot.Knot;
import app.zipper.knot.KnotConfig;
import app.zipper.knot.LineVersion;
import app.zipper.knot.LoadParam;
import app.zipper.knot.Reflect;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * Adds an image preview to LINE image-message notifications without blocking LINE's notify() call.
 *
 * <p>This first implementation intentionally avoids the old /Android/data directory scan. It reads
 * the exact attachment URI from naver_line.chat_history and updates the already-posted notification
 * asynchronously. It also logs only the names/presence of OBS/E2EE metadata needed for the direct
 * OBS implementation; media keys themselves are never logged.
 */
public class ImageNotificationPreviewHook implements BaseHook {
  static final String REPOST_MARKER = "knot.image_notification_preview_repost";

  private static final int ATTACHMENT_IMAGE = 1;
  private static final int DB_ATTEMPTS = 30;
  private static final int FILE_ATTEMPTS = 30;
  private static final long RETRY_DELAY_MS = 50L;
  private static final int MAX_BITMAP_DIMENSION = 1200;

  private static final ExecutorService executor =
      Executors.newFixedThreadPool(
          2,
          runnable -> {
            Thread thread = new Thread(runnable, "Knot-ImageNotification");
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

              // Never delay the original LINE notification. Image lookup happens after it is posted.
              Object result = chain.proceed();
              executor.execute(() -> updateImageNotification(tag, id, notification, messageId));
              return result;
            });
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
    LineVersion.Config.Notification cfg = version.notification;
    return sameNonEmpty(tag, cfg.messageNotificationTag) || sameNonEmpty(tag, cfg.chatNotificationTag);
  }

  private static void updateImageNotification(
      String tag, int id, Notification original, String messageId) {
    try {
      Context context = Knot.currentApplication();
      if (context == null) return;

      ImageRow row = awaitImageRow(context, messageId);
      if (row == null || row.attachmentType != ATTACHMENT_IMAGE) return;

      logProbe(messageId, row);

      Bitmap bitmap = awaitLocalBitmap(context, row.localUri);
      if (bitmap == null) {
        Knot.log(
            "Knot: image preview: local image not ready; OBS direct path metadata probe completed"
                + " for message="
                + messageId);
        return;
      }

      repost(context, tag, id, original, bitmap);
      Knot.log("Knot: image preview: notification updated for message=" + messageId);
    } catch (Throwable t) {
      Knot.log("Knot: image preview failed: " + t);
    }
  }

  private static ImageRow awaitImageRow(Context context, String messageId) {
    ImageRow lastImage = null;
    for (int attempt = 0; attempt < DB_ATTEMPTS; attempt++) {
      ImageRow row = readImageRow(context, messageId);
      if (row != null && row.attachmentType == ATTACHMENT_IMAGE) {
        lastImage = row;
        if (hasText(row.localUri)) return row;
      }
      if (!sleepBriefly()) break;
    }
    return lastImage;
  }

  private static ImageRow readImageRow(Context context, String messageId) {
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

      ImageRow row = new ImageRow();
      row.localId = getString(cursor, "id");
      row.chatId = getString(cursor, "chat_id");
      row.localUri = getString(cursor, "attachement_local_uri");
      row.parameter = getString(cursor, "parameter");
      row.attachmentType = getInt(cursor, "attachement_type", -1);
      return row;
    } catch (Throwable ignored) {
      return null;
    } finally {
      if (cursor != null) cursor.close();
      if (db != null) db.close();
    }
  }

  private static Bitmap awaitLocalBitmap(Context context, String localUri) {
    if (!hasText(localUri)) return null;
    for (int attempt = 0; attempt < FILE_ATTEMPTS; attempt++) {
      Bitmap bitmap = decodeLocalBitmap(context, localUri);
      if (bitmap != null) return bitmap;
      if (!sleepBriefly()) break;
    }
    return null;
  }

  private static Bitmap decodeLocalBitmap(Context context, String value) {
    try {
      Uri uri = Uri.parse(value);
      String scheme = uri.getScheme();
      if (scheme == null || scheme.isEmpty() || "file".equalsIgnoreCase(scheme)) {
        String path = "file".equalsIgnoreCase(scheme) ? uri.getPath() : value;
        if (!hasText(path)) return null;
        File file = new File(path);
        if (!file.isFile() || file.length() <= 0) return null;
        return decodeFileScaled(file.getAbsolutePath());
      }

      if ("content".equalsIgnoreCase(scheme)) {
        ContentResolver resolver = context.getContentResolver();
        try (InputStream in = resolver.openInputStream(uri)) {
          if (in == null) return null;
          Bitmap bitmap = BitmapFactory.decodeStream(in);
          return scaleDown(bitmap);
        }
      }
    } catch (Throwable ignored) {
    }
    return null;
  }

  private static Bitmap decodeFileScaled(String path) {
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeFile(path, bounds);
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

    int sample = 1;
    while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_BITMAP_DIMENSION) {
      sample <<= 1;
    }

    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inSampleSize = sample;
    return scaleDown(BitmapFactory.decodeFile(path, options));
  }

  private static Bitmap scaleDown(Bitmap bitmap) {
    if (bitmap == null) return null;
    int width = bitmap.getWidth();
    int height = bitmap.getHeight();
    int max = Math.max(width, height);
    if (max <= MAX_BITMAP_DIMENSION) return bitmap;

    float scale = (float) MAX_BITMAP_DIMENSION / (float) max;
    int outWidth = Math.max(1, Math.round(width * scale));
    int outHeight = Math.max(1, Math.round(height * scale));
    Bitmap scaled = Bitmap.createScaledBitmap(bitmap, outWidth, outHeight, true);
    if (scaled != bitmap) bitmap.recycle();
    return scaled;
  }

  private static void repost(
      Context context, String tag, int id, Notification original, Bitmap bitmap) {
    try {
      Notification.Builder builder = Notification.Builder.recoverBuilder(context, original);
      Bundle marker = new Bundle();
      marker.putBoolean(REPOST_MARKER, true);
      builder.addExtras(marker);
      builder.setOnlyAlertOnce(true);
      builder.setStyle(
          new Notification.BigPictureStyle()
              .bigPicture(bitmap)
              .setSummaryText(original.extras.getCharSequence(Notification.EXTRA_TEXT)));

      NotificationManager nm =
          (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
      if (nm == null) return;

      reposting.set(Boolean.TRUE);
      try {
        if (tag == null) {
          nm.notify(id, builder.build());
        } else {
          nm.notify(tag, id, builder.build());
        }
      } finally {
        reposting.remove();
      }
    } catch (Throwable t) {
      Knot.log("Knot: image preview repost failed: " + t);
    }
  }

  private static void logProbe(String messageId, ImageRow row) {
    Map<String, String> metadata = parseParameter(row.parameter);
    List<String> keys = new ArrayList<>(metadata.keySet());
    String oid = firstValue(metadata, "OID", "oid");
    String sid = firstValue(metadata, "SID", "sid");
    String keyMaterial = firstValue(metadata, "ENC_KM", "keyMaterial", "KEY_MATERIAL");

    String uriInfo = "none";
    if (hasText(row.localUri)) {
      try {
        Uri uri = Uri.parse(row.localUri);
        uriInfo = uri.getScheme() == null ? "path" : uri.getScheme();
      } catch (Throwable ignored) {
        uriInfo = "unknown";
      }
    }

    Knot.log(
        "Knot: image preview probe: message="
            + messageId
            + " localId="
            + safe(row.localId)
            + " chat="
            + safe(row.chatId)
            + " uri="
            + uriInfo
            + " parameterKeys="
            + keys
            + " SID="
            + safe(sid)
            + " OID="
            + safe(oid)
            + " mediaKey="
            + (hasText(keyMaterial) ? "present(len=" + keyMaterial.length() + ")" : "absent"));
  }

  private static Map<String, String> parseParameter(String parameter) {
    Map<String, String> out = new LinkedHashMap<>();
    if (!hasText(parameter)) return out;

    String trimmed = parameter.trim();
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
      try {
        JSONObject json = new JSONObject(trimmed);
        java.util.Iterator<String> it = json.keys();
        while (it.hasNext()) {
          String key = it.next();
          Object value = json.opt(key);
          out.put(key, value == null ? "" : String.valueOf(value));
        }
        return out;
      } catch (Throwable ignored) {
      }
    }

    String[] parts = parameter.split("\\t", -1);
    for (int i = 0; i + 1 < parts.length; i += 2) {
      if (!parts[i].isEmpty()) out.put(parts[i], parts[i + 1]);
    }
    return out;
  }

  private static String firstValue(Map<String, String> map, String... candidates) {
    for (String candidate : candidates) {
      for (Map.Entry<String, String> entry : map.entrySet()) {
        if (candidate.equalsIgnoreCase(entry.getKey())) return entry.getValue();
      }
    }
    return null;
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

  private static String safe(String value) {
    return hasText(value) ? value : "-";
  }

  private static final class ImageRow {
    String localId;
    String chatId;
    int attachmentType;
    String localUri;
    String parameter;
  }
}
