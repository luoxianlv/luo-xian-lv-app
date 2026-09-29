package app.luoxianlv.business.ui

import android.content.Context
import android.content.ContextWrapper

/** 长期渲染器保留本代资源和加载器，不持有已经销毁的 Activity。 */
fun Context.resourceApplicationContext(): Context {
    val resources = resources
    val loader = classLoader
    return object : ContextWrapper(applicationContext) {
        override fun getResources() = resources

        override fun getAssets() = resources.assets

        override fun getClassLoader() = loader
    }
}
