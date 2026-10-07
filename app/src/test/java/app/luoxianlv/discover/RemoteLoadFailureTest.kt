package app.luoxianlv.discover

import app.luoxianlv.platform.PlatformScore
import org.junit.Assert.*
import org.junit.Test

class RemoteLoadFailureTest {
    private val scores = (0 until 60).map { PlatformScore("id-$it", "曲目 $it", "作者", 120, null, "") }

    @Test
    fun prefetchFailureKeepsCachedSongsAndStopsAutomaticNetworkRetries() {
        val failed =
            RemoteUiState(scores = scores, visibleCount = 30, nextPage = 3, loadingMore = true)
                .withPaginationFailure()

        assertEquals(scores.take(30), failed.visibleScores)
        assertNull(failed.error)
        assertFalse(failed.loadingMore)
        // 网络失败不阻止显示已经预取到的第二页。
        assertTrue(failed.canAutoLoadMore)
        val exhausted = failed.copy(visibleCount = scores.size)
        assertTrue(exhausted.hasMore)
        assertFalse(exhausted.canAutoLoadMore)
        assertEquals(3, exhausted.nextPage)
    }

    @Test
    fun failedRefreshRestoresDiscoverCacheInsteadOfSharedSearchResults() {
        val failed =
            RemoteUiState(
                    loading = true,
                    scores = listOf(PlatformScore("search", "搜索结果", "作者", 120, null, "")),
                    visibleCount = 1,
                )
                .withFeedFailure(scores, cachedNextPage = 3, cachedTotal = 100)

        assertEquals(scores.take(30), failed.visibleScores)
        assertEquals("共 100 首公开谱子", failed.status)
        assertNull(failed.error)
        assertFalse(failed.loading)
        assertTrue(failed.hasMore)
    }

    @Test
    fun coldLoadFailureLeavesAnInlineRetryStateWithoutAnErrorNotice() {
        val failed =
            RemoteUiState(loading = true)
                .withFeedFailure(null, cachedNextPage = null, cachedTotal = 0)

        assertFalse(failed.loading)
        assertNull(failed.error)
        assertTrue(failed.visibleScores.isEmpty())
        assertEquals("暂时无法加载谱子，请稍后重试", failed.status)
        assertFalse(failed.canAutoLoadMore)
    }

    @Test
    fun downloadNoticeDoesNotBlockPaginationOrGetOverwrittenByPrefetchFailure() {
        val state =
            RemoteUiState(
                scores = scores.take(30),
                visibleCount = 30,
                nextPage = 2,
                error = "谱子暂时无法下载，请稍后重试",
            )

        assertTrue(state.canAutoLoadMore)
        val failed = state.withPaginationFailure()
        assertEquals(state.error, failed.error)
        assertFalse(failed.canAutoLoadMore)
    }
}
