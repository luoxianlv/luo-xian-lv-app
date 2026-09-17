package app.luoxianlv.ui

import app.luoxianlv.data.Song
import app.luoxianlv.service.MusicAccessibilityService

/**
 * 把曲目同步给无障碍服务，让悬浮窗立刻切到这首。
 *
 * 曲库选择、谱面导入、平台下载三处都要做这一步，原本各写一遍
 * `MusicAccessibilityService.instance?.select(song)`。统一走这里，
 * 服务未开启时静默跳过。
 */
fun syncSelectionToService(song: Song) {
    MusicAccessibilityService.instance?.select(song)
}
