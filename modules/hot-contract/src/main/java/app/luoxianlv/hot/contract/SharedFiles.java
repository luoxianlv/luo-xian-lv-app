package app.luoxianlv.hot.contract;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.res.XmlResourceParser;
import android.net.Uri;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/** 宿主与业务共用的只读分享地址，沿用已发布的 authority、根别名和 XML 路径配置。 */
public final class SharedFiles {
  private static final Map<String, Map<String, File>> CACHE = new LinkedHashMap<>();
  private static final String PATHS = "android.support.FILE_PROVIDER_PATHS";

  private SharedFiles() {}

  public static Uri getUriForFile(Context context, String authority, File file) {
    File canonical = canonical(file);
    String selected = null;
    File root = null;
    for (Map.Entry<String, File> entry : roots(context, authority).entrySet()) {
      if (inside(entry.getValue(), canonical)
          && (root == null || entry.getValue().getPath().length() > root.getPath().length())) {
        selected = entry.getKey();
        root = entry.getValue();
      }
    }
    if (root == null) throw new IllegalArgumentException("文件不在允许分享的目录内");
    String relative = canonical.getPath().substring(root.getPath().length() + 1);
    return new Uri.Builder()
        .scheme("content")
        .authority(authority)
        .encodedPath(Uri.encode(selected) + "/" + Uri.encode(relative, "/"))
        .build();
  }

  public static File resolve(Context context, Uri uri) {
    if (!"content".equals(uri.getScheme()) || uri.getFragment() != null)
      throw new SecurityException("不支持的分享地址");
    String path = uri.getEncodedPath();
    int separator = path == null ? -1 : path.indexOf('/', 1);
    if (separator < 2 || separator == path.length() - 1)
      throw new IllegalArgumentException("分享地址缺少目录或文件");
    File root = roots(context, uri.getAuthority()).get(Uri.decode(path.substring(1, separator)));
    if (root == null) throw new SecurityException("未知的分享目录");
    String relative = Uri.decode(path.substring(separator + 1));
    if (relative.startsWith("/") || relative.indexOf('\0') >= 0)
      throw new SecurityException("无效的相对路径");
    File file = canonical(new File(root, relative));
    if (!inside(root, file)) throw new SecurityException("分享路径超出允许目录");
    return file;
  }

  private static synchronized Map<String, File> roots(Context context, String authority) {
    if (!(context.getPackageName() + ".updates").equals(authority))
      throw new SecurityException("分享地址不属于当前应用");
    Map<String, File> cached = CACHE.get(authority);
    if (cached != null) return cached;
    PackageManager manager = context.getPackageManager();
    ProviderInfo info = manager.resolveContentProvider(authority, PackageManager.GET_META_DATA);
    if (info == null
        || info.exported
        || !info.grantUriPermissions
        || !context.getPackageName().equals(info.packageName))
      throw new SecurityException("分享组件必须为本应用的非公开、按地址授权入口");
    Map<String, File> result = new LinkedHashMap<>();
    try (XmlResourceParser parser = info.loadXmlMetaData(manager, PATHS)) {
      if (parser == null) throw new IllegalArgumentException("缺少分享目录配置");
      while (parser.next() != XmlPullParser.END_DOCUMENT) {
        if (parser.getEventType() != XmlPullParser.START_TAG) continue;
        String tag = parser.getName();
        if (tag.equals("paths")) continue;
        File base;
        switch (tag) {
          case "cache-path":
            base = context.getCacheDir();
            break;
          case "files-path":
            base = context.getFilesDir();
            break;
          case "external-files-path":
            base = context.getExternalFilesDir(null);
            break;
          default:
            throw new IllegalArgumentException("不允许分享此类根目录：" + tag);
        }
        String name = parser.getAttributeValue(null, "name");
        String path = parser.getAttributeValue(null, "path");
        if (name == null
            || name.isEmpty()
            || result.containsKey(name)
            || path == null
            || path.isEmpty()
            || new File(path).isAbsolute()) throw new IllegalArgumentException("分享目录配置无效或重复");
        if (base == null) continue;
        File root = canonical(new File(base, path));
        if (!inside(canonical(base), root)) throw new SecurityException("分享目录必须是专用子目录");
        result.put(name, root);
      }
    } catch (IOException | XmlPullParserException failure) {
      throw new IllegalArgumentException("无法读取分享目录配置", failure);
    }
    CACHE.put(authority, result);
    return result;
  }

  private static boolean inside(File root, File file) {
    return file.getPath().startsWith(root.getPath() + File.separator);
  }

  private static File canonical(File file) {
    try {
      return file.getCanonicalFile();
    } catch (IOException failure) {
      throw new IllegalArgumentException("无法解析文件路径", failure);
    }
  }
}
