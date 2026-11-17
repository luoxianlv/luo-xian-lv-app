package app.luoxianlv.host;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;

/** 空白模拟器的旧宿主种子与系统升级后检查，不改正式用户数据。 */
public final class UpgradeDataInstrumentation extends Instrumentation {
  private boolean seed;
  private static final String WALLPAPER = "6fa2a5bb-9afb-414d-a909-3804a6294609";
  @Override public void onCreate(Bundle args) {seed="true".equals(args.getString("seed"));super.onCreate(args);start();}
  @Override public void onStart() {
    Bundle report=new Bundle(); boolean passed=false;
    try {
      if (!getTargetContext().getPackageName().equals("app.luoxianlv.debug")) throw new AssertionError("仅限测试包");
      startActivitySync(new Intent().setClassName(getTargetContext(),"app.luoxianlv.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      long until=SystemClock.elapsedRealtime()+30000;
      while(true) {
        try {if(Bootstrap.source().prepared!=null)break;} catch(IllegalStateException waiting) {}
        if(SystemClock.elapsedRealtime()>until)throw new AssertionError("业务未加载");
        SystemClock.sleep(25);
      }
      var selected=new AtomicReference<Context>();
      runOnMainSync(()->selected.set(Bootstrap.source().prepared.context(getTargetContext())));
      Context context=selected.get(); ClassLoader loader=Bootstrap.source().prepared.classLoader();
      Class<?> type=loader.loadClass("app.luoxianlv.library.SongRepository");
      Object repository=type.getConstructor(Context.class).newInstance(context);
      SharedPreferences evidence=getTargetContext().getSharedPreferences("ensemble_upgrade_test",0);
      File folder=new File(context.getExternalFilesDir(null),"wallpapers/"+WALLPAPER);
      SharedPreferences wallpaper=context.getSharedPreferences("practice_wallpaper",0);
      if(seed) {
        if(evidence.contains("song"))throw new AssertionError("请用空白测试安装");
        Object song=type.getMethod("add",String.class,String.class,int.class,String.class,String.class,String.class)
            .invoke(repository,"升级保留测试","1 2 3 4",120,"本机验收","","");
        String id=(String)song.getClass().getMethod("getId").invoke(song);
        type.getMethod("setSpeed",float.class).invoke(repository,1.25f);
        if(!folder.mkdirs() && !folder.isDirectory())throw new AssertionError("测试壁纸目录未创建");
        Files.write(new File(folder,".root").toPath(),new byte[0]);
        Files.write(new File(folder,"project.json").toPath(),"{\"type\":\"web\",\"file\":\"index.html\",\"title\":\"升级保留测试\"}".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(folder,"index.html").toPath(),"<!doctype html><body style='background:#182338'>升级保留</body>".getBytes(StandardCharsets.UTF_8));
        if(!wallpaper.edit().putString("project",WALLPAPER).putBoolean("sound",true).putInt("minute",600).commit())throw new AssertionError("壁纸设置未落盘");
        String services=Settings.Secure.getString(context.getContentResolver(),"enabled_accessibility_services");
        if(!evidence.edit().putString("song",id).putString("accessibility",services).commit())throw new AssertionError("种子未保存");
        // FastKV 的异步落盘仍需在旧进程退出前完成。
        SystemClock.sleep(1500);
      } else {
        Object song=type.getMethod("selected").invoke(repository);
        if(!evidence.getString("song","").equals(song.getClass().getMethod("getId").invoke(song)))throw new AssertionError("选中谱子丢失");
        if(!"升级保留测试".equals(song.getClass().getMethod("getTitle").invoke(song)))throw new AssertionError("谱子内容丢失");
        if((float)type.getMethod("getSpeed").invoke(repository)!=1.25f)throw new AssertionError("速度设置丢失");
        if(!wallpaper.getBoolean("sound",false) || wallpaper.getInt("minute",-1)!=600 || !WALLPAPER.equals(wallpaper.getString("project","")))throw new AssertionError("壁纸设置丢失");
        if(!new String(Files.readAllBytes(new File(folder,"index.html").toPath()),StandardCharsets.UTF_8).contains("升级保留"))throw new AssertionError("壁纸项目丢失");
        if(!java.util.Objects.equals(evidence.getString("accessibility",null),Settings.Secure.getString(context.getContentResolver(),"enabled_accessibility_services")))throw new AssertionError("无障碍授权改变");
      }
      report.putString("stream",seed?"旧宿主升级种子已保存\n":"系统升级通过：谱子、选中项、速度、壁纸原文件/设置和无障碍授权保留\n");passed=true;
    } catch(Throwable failure) {report.putString("stream",android.util.Log.getStackTraceString(failure));}
    finish(passed?-1:0,report);
  }
}
