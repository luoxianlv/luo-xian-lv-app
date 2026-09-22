package app.luoxianlv.ui.theme

import androidx.compose.ui.graphics.Color

// 迁移自 values/colors.xml 的品牌色板
val PlayerPrimary = Color(0xFF0A84FF)
val PlayerPrimaryDark = Color(0xFF006EDB)
val PlayerAccent = Color(0xFF18A999)

/** 开关（SmallSwitch）勾选轨道的强调蓝：QQ 蓝，比主色更亮更清透 */
val PlayerQqBlue = Color(0xFF12B7F5)
val PlayerBg = Color(0xFFF6F7FB)
val PlayerSurface = Color(0xFFFFFFFF)
val PlayerSurfaceTint = Color(0xFFEAF3FF)
val PlayerText = Color(0xFF17191D)
val PlayerTextSecondary = Color(0xFF697386)
val PlayerDivider = Color(0xFFE5E8EF)
val PlayerDanger = Color(0xFFD95B67)

// ---------------------------------------------------------------------------
// 深色色板
//
// 深色不是把浅色反相：整个设计的骨架是「渐变底 + 半透明容器」，
// 所以深色要重做的只有底和容器这两层。取值受三条约束：
// 1. **底比容器深**：浅色里白卡压在蓝灰渐变上；深色里容器必须比渐变底更亮，
//    否则卡片会变成「挖空的洞」而不是浮起来的板子（见 PlayerSurfaceNight）。
// 2. **底色不能是纯黑**：纯黑上做不出渐变层次，且与品牌蓝调脱节；
//    这里沿用浅色那支渐变的色相，整体压到深藏青（见 Backdrop.kt）。
// 3. **字色留够对比**：PlayerTextNight 压在容器上约 13:1，
//    PlayerTextSecondaryNight 约 5:1，都在可读区间内。
// ---------------------------------------------------------------------------

/** 深色主色：品牌蓝提亮一档，原色 #0A84FF 压在深底上会显得发闷。 */
val PlayerPrimaryNight = Color(0xFF4DA3FF)

/** 亮蓝底上的前景色：深色主题里实心主色块配深字，不是白字。 */
val PlayerOnPrimaryNight = Color(0xFF00284D)

/** 导航栏选中胶囊的底色：比卡片再亮一档的石板蓝。 */
val PlayerPrimaryContainerNight = Color(0xFF33445F)
val PlayerOnPrimaryContainerNight = Color(0xFFA9D2FF)

val PlayerAccentNight = Color(0xFF3FD0BE)

/** 窗口底色：只在启动窗口与系统栏上可见（页面自己铺渐变底）。 */
val PlayerBgNight = Color(0xFF0F1420)

/**
 * 半透明容器的基色。
 *
 * 刻意比渐变底亮：#3B4863 压到 0.62 后大约是 (49,62,88)，
 * 与底色 (27,36,54) 拉开一档，卡片、导航栏、选项卡才是「浮起来的板子」。
 * 换成 Material 深色的近黑 surface 就会反过来比底还暗。
 *
 * 实测（模拟器截图取色）：深色下容器与底/渐变底的对比度 1.16–1.17，
 * 浅色对应位置是 1.01–1.11 —— 暗处人眼分辨亮度差的能力更弱，
 * 所以取值往亮的一侧靠了靠，而不是沿用浅色的差值。
 */
val PlayerSurfaceNight = Color(0xFF3B4863)

/** 次级容器：开关轨道、状态胶囊、列表选中行。比 surface 略暗，压在亮容器上仍看得出。 */
val PlayerSurfaceVariantNight = Color(0xFF2E3A50)

/** 对话框 / 下拉菜单的底：浮层必须不透明（跟着半透明只会更难辨认）。 */
val PlayerContainerNight = Color(0xFF232C3E)
val PlayerContainerHighNight = Color(0xFF2A3448)

val PlayerTextNight = Color(0xFFE8ECF4)
val PlayerTextSecondaryNight = Color(0xFF9AA6BC)
val PlayerDividerNight = Color(0xFF35415A)
val PlayerDangerNight = Color(0xFFFF7A85)

/** 深色下的开关轨道强调蓝：QQ 蓝提亮，深底上才够跳。 */
val PlayerQqBlueNight = Color(0xFF3FC6F7)
