package app.luoxianlv.hot;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import app.luoxianlv.hot.contract.SharedFiles;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Locale;

/** 安装包和诊断包的稳定只读入口，无 AndroidX/业务加载依赖。 */
public final class SharedFileProvider extends ContentProvider {
  @Override
  public void attachInfo(Context context, ProviderInfo info) {
    if (info.exported
        || !info.grantUriPermissions
        || !(context.getPackageName() + ".updates").equals(info.authority))
      throw new SecurityException("分享组件仅允许当前应用按地址授予读取权限");
    super.attachInfo(context, info);
  }

  @Override
  public boolean onCreate() {
    return true;
  }

  @Override
  public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
    File file = SharedFiles.resolve(getContext(), uri);
    String[] requested =
        projection == null
            ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
            : projection;
    ArrayList<String> columns = new ArrayList<>();
    ArrayList<Object> values = new ArrayList<>();
    for (String column : requested) {
      if (OpenableColumns.DISPLAY_NAME.equals(column)) {
        columns.add(column);
        values.add(file.getName());
      } else if (OpenableColumns.SIZE.equals(column)) {
        columns.add(column);
        values.add(file.length());
      }
    }
    MatrixCursor cursor = new MatrixCursor(columns.toArray(new String[0]), 1);
    cursor.addRow(values.toArray());
    return cursor;
  }

  @Override
  public String getType(Uri uri) {
    String name = SharedFiles.resolve(getContext(), uri).getName();
    int dot = name.lastIndexOf('.');
    String mime =
        dot < 0
            ? null
            : MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    return mime == null ? "application/octet-stream" : mime;
  }

  @Override
  public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    if (!"r".equals(mode)) throw new SecurityException("分享文件只允许读取");
    File file = SharedFiles.resolve(getContext(), uri);
    if (!file.isFile()) throw new FileNotFoundException("分享文件不存在或不是普通文件");
    return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
  }

  @Override
  public Uri insert(Uri uri, ContentValues values) {
    throw new UnsupportedOperationException("分享入口不允许写入");
  }

  @Override
  public int update(Uri uri, ContentValues values, String selection, String[] args) {
    throw new UnsupportedOperationException("分享入口不允许修改");
  }

  @Override
  public int delete(Uri uri, String selection, String[] args) {
    throw new UnsupportedOperationException("分享入口不允许删除");
  }
}
