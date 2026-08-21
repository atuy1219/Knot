package app.zipper.knot.hooks;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import app.zipper.knot.Knot;
import app.zipper.knot.KnotConfig;
import app.zipper.knot.LoadParam;
import app.zipper.knot.Reflect;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ModuleDrawableCompatHook implements BaseHook {

  private static final String MODULE_PKG = "app.zipper.knot";
  private static final int ID_READ_ON = 0x64000001;
  private static final int ID_READ_OFF = 0x64000002;
  private static final int ID_MARK_ON = 0x64000003;
  private static final int ID_MARK_OFF = 0x64000004;
  private static final int ID_EDIT_HISTORY = 0x64000010;

  private static final Map<Integer, Bitmap> CACHE = new ConcurrentHashMap<>();

  @Override
  public void hook(KnotConfig config, LoadParam lpparam) throws Throwable {
    Knot.module
        .hook(Reflect.findMethodExact(Resources.class, "getDrawable", int.class))
        .intercept(
            chain -> {
              int id = (int) chain.getArg(0);
              String name = drawableName(id);
              if (name == null) return chain.proceed();

              Bitmap bitmap = loadBitmap(id, name);
              if (bitmap == null) return chain.proceed();
              return new BitmapDrawable((Resources) chain.getThisObject(), bitmap);
            });
  }

  private static String drawableName(int id) {
    switch (id) {
      case ID_READ_ON:
        return "ic_prevent_read_on";
      case ID_READ_OFF:
        return "ic_prevent_read_off";
      case ID_MARK_ON:
        return "ic_send_mark_read_on";
      case ID_MARK_OFF:
        return "ic_send_mark_read_off";
      case ID_EDIT_HISTORY:
        return "clock_edit";
      default:
        return null;
    }
  }

  private static Bitmap loadBitmap(int id, String name) {
    Bitmap cached = CACHE.get(id);
    if (cached != null) return cached;

    try {
      Context appCtx = Knot.currentApplication();
      if (appCtx == null) return null;
      Context moduleCtx =
          appCtx.createPackageContext(MODULE_PKG, Context.CONTEXT_IGNORE_SECURITY);
      Resources resources = moduleCtx.getResources();
      int resId = resources.getIdentifier(name, "drawable", MODULE_PKG);
      if (resId == 0) return null;

      Drawable drawable = resources.getDrawable(resId, null);
      if (!(drawable instanceof BitmapDrawable)) return null;
      Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
      if (bitmap != null) CACHE.put(id, bitmap);
      return bitmap;
    } catch (Throwable t) {
      return null;
    }
  }
}
