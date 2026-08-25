package app.zipper.knot.hooks;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import app.zipper.knot.Knot;
import app.zipper.knot.KnotConfig;
import app.zipper.knot.LoadParam;
import app.zipper.knot.Reflect;

/**
 * Captures LINE's already-decoded message fields before chat_history persistence.
 *
 * <p>This sits upstream of the notification hooks: the ContentValues passed to SQLite already
 * contains LINE's post-E2EE message representation, but the row has not necessarily been committed
 * yet. The notification hooks can therefore use memory first and retain database lookup only as a
 * compatibility fallback.
 */
public class MessageCaptureHook implements BaseHook {
  @Override
  public void hook(KnotConfig config, LoadParam lpparam) throws Throwable {
    if (!config.imageNotificationPreview.enabled) return;

    hookInsert();
    hookUpdate();
  }

  private static void hookInsert() throws Throwable {
    Knot.module
        .hook(
            Reflect.findMethodExact(
                SQLiteDatabase.class,
                "insertWithOnConflict",
                String.class,
                String.class,
                ContentValues.class,
                int.class))
        .intercept(
            chain -> {
              try {
                String table = (String) chain.getArg(0);
                ContentValues values = (ContentValues) chain.getArg(2);
                CapturedMessageStore.MessageData captured =
                    CapturedMessageStore.capture(table, values, null);
                if (captured != null) ImageNotificationPreviewHook.prefetchCapturedImage(captured);
              } catch (Throwable t) {
                Knot.log("Knot: message pre-capture insert failed: " + t.getClass().getSimpleName());
              }
              return chain.proceed();
            });
  }

  private static void hookUpdate() throws Throwable {
    Knot.module
        .hook(
            Reflect.findMethodExact(
                SQLiteDatabase.class,
                "updateWithOnConflict",
                String.class,
                ContentValues.class,
                String.class,
                String[].class,
                int.class))
        .intercept(
            chain -> {
              try {
                String table = (String) chain.getArg(0);
                ContentValues values = (ContentValues) chain.getArg(1);
                String whereClause = (String) chain.getArg(2);
                String[] whereArgs = (String[]) chain.getArg(3);
                String serverId = serverIdFromWhere(whereClause, whereArgs);
                CapturedMessageStore.MessageData captured =
                    CapturedMessageStore.capture(table, values, serverId);
                if (captured != null) ImageNotificationPreviewHook.prefetchCapturedImage(captured);
              } catch (Throwable t) {
                Knot.log("Knot: message pre-capture update failed: " + t.getClass().getSimpleName());
              }
              return chain.proceed();
            });
  }

  private static String serverIdFromWhere(String whereClause, String[] whereArgs) {
    if (whereClause == null || whereArgs == null || whereArgs.length == 0) return null;
    String normalized = whereClause.replace("`", "").replace("\"", "").toLowerCase();
    if (!normalized.contains("server_id") || !normalized.contains("?")) return null;
    // Most LINE DAO updates use a single server_id placeholder. Avoid guessing for compound clauses.
    int placeholders = 0;
    for (int i = 0; i < normalized.length(); i++) {
      if (normalized.charAt(i) == '?') placeholders++;
    }
    if (placeholders != 1) return null;
    return whereArgs[0];
  }
}
