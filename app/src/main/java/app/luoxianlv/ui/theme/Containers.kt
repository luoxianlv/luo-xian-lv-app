package app.luoxianlv.ui.theme

import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/** 渐变底上的容器透明度。0.78 时仍近似纯白，0.55 时顶部约为 (216,237,246)，且说明文字 #697386 的对比度约 3.8:1；调整时同时校验层次和可读性。 */
const val ON_BACKDROP_SURFACE_ALPHA = 0.55f

/**
 * 深色容器用 0.62：#3B4863 与渐变 #1B2436..#141A28 混合后约为 (49,62,88)，能区分面板与底色；[PlayerTextSecondaryNight] 对比度约
 * 5:1。
 */
const val ON_BACKDROP_SURFACE_ALPHA_DARK = 0.62f

/** 按主题 surface 的 alpha 判断容器是否半透明，供统一阴影策略使用。 */
@Composable fun translucentContainers(): Boolean = MaterialTheme.colorScheme.surface.alpha < 1f

/** 统一卡片阴影：半透明填充会露出底层阴影而发灰，因此禁用抬升；不另加描边，依靠背景明暗区分层次。不透明容器使用 1 dp 抬升。 */
@Composable
fun containerElevation(): CardElevation =
    CardDefaults.cardElevation(defaultElevation = if (translucentContainers()) 0.dp else 1.dp)
