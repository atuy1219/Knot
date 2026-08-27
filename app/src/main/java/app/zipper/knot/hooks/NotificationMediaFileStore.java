package app.zipper.knot.hooks;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import app.zipper.knot.Knot;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Persists notification media under LINE's existing shareable FileProvider root. */
final class NotificationMediaFileStore {
  private static final String LINE_FILE_PROVIDER =
      "jp.naver.line.android.common.LineCommonFileProvider";
  private static final String DIRECTORY = "knot_notification_media";
  private static final long FILE_TTL_MS = 24L * 60L * 60L * 1000L;

  private static volatile Method uriForFileMethod;

  private NotificationMediaFileStore() {}

  static synchronized Attachment put(
      Context context, String messageId, Bitmap bitmap, String mimeType) {
    if (context == null || !hasText(messageId) || bitmap == null || !hasText(mimeType)) {
      return null;
    }

    try {
      File root = new File(new File(context.getCacheDir(), "external_share"), DIRECTORY);
      if (!root.isDirectory() && !root.mkdirs()) return null;
      prune(root);

      boolean png = "image/png".equalsIgnoreCase(mimeType);
      File file = new File(root, digest(messageId) + (png ? ".png" : ".jpg"));
      try (FileOutputStream out = new FileOutputStream(file, false)) {
        Bitmap.CompressFormat format = png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG;
        if (!bitmap.compress(format, png ? 100 : 88, out)) return null;
        out.flush();
      }

      Uri uri = uriForFile(context, file);
      if (uri == null) return null;
      try {
        context.grantUriPermission(
            "com.android.systemui", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
      } catch (Throwable ignored) {
        // NotificationManager also grants data URIs while the notification is active.
      }
      return new Attachment(mimeType, uri);
    } catch (Throwable t) {
      Knot.log("Knot: notification media URI unavailable: " + t.getClass().getSimpleName());
      return null;
    }
  }

  private static Uri uriForFile(Context context, File file) throws Exception {
    Method method = uriForFileMethod;
    if (method == null) {
      Class<?> provider =
          Class.forName(LINE_FILE_PROVIDER, false, context.getClassLoader());
      for (Class<?> nested : provider.getDeclaredClasses()) {
        for (Method candidate : nested.getDeclaredMethods()) {
          Class<?>[] parameters = candidate.getParameterTypes();
          if (Modifier.isStatic(candidate.getModifiers())
              && candidate.getReturnType() == Uri.class
              && parameters.length == 2
              && parameters[0] == Context.class
              && parameters[1] == File.class) {
            candidate.setAccessible(true);
            method = candidate;
            break;
          }
        }
        if (method != null) break;
      }
      if (method == null) throw new NoSuchMethodException("LINE FileProvider URI helper");
      uriForFileMethod = method;
    }
    Object result = method.invoke(null, context, file);
    return result instanceof Uri ? (Uri) result : null;
  }

  private static void prune(File root) {
    File[] files = root.listFiles();
    if (files == null) return;
    long cutoff = System.currentTimeMillis() - FILE_TTL_MS;
    for (File file : files) {
      if (file.isFile() && file.lastModified() < cutoff) {
        try {
          file.delete();
        } catch (Throwable ignored) {
        }
      }
    }
  }

  private static String digest(String value) throws Exception {
    byte[] hash =
        MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    StringBuilder out = new StringBuilder(32);
    for (int i = 0; i < 16; i++) {
      out.append(Character.forDigit((hash[i] >>> 4) & 0x0F, 16));
      out.append(Character.forDigit(hash[i] & 0x0F, 16));
    }
    return out.toString();
  }

  private static boolean hasText(String value) {
    return value != null && !value.isEmpty();
  }

  static final class Attachment {
    final String mimeType;
    final Uri uri;

    Attachment(String mimeType, Uri uri) {
      this.mimeType = mimeType;
      this.uri = uri;
    }
  }
}
