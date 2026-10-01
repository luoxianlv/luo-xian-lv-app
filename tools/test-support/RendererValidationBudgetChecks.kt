fun main() {
    var checks = 0
    fun expect(actual: Long?, expected: Long?) { check(actual == expected) { "$actual != $expected" }; checks++ }
    val visual = RendererValidationBudget(12000)
    expect(visual.update(0, false), null) // Actual Decor has not attached yet.
    expect(visual.update(60000, false), null)
    expect(visual.update(60000, true), 12000)
    expect(visual.update(62000, true), 10000)
    expect(visual.update(63000, false), null) // HOME after 3 seconds of displayable work.
    expect(visual.update(363000, false), null) // Five minutes in background do not consume visual time.
    expect(visual.update(363000, true), 9000)
    expect(visual.update(371999, true), 1)
    expect(visual.update(372000, true), 0) // Bad visible renderer still exhausts its finite budget.
    val service = RendererValidationBudget(12000)
    expect(service.update(0, true), 12000) // JS-only service remains eligible without a window.
    expect(service.update(11999, true), 1)
    expect(service.update(12000, true), 0)
    val cancelled = RendererValidationBudget(12000)
    expect(cancelled.update(0, true), 12000)
    cancelled.close()
    expect(cancelled.update(12000, true), null) // A queued timer cannot revive a closed job.
    val cumulative = RendererValidationBudget(12000)
    for (i in 0..2) {
        val start = i * 100000L
        expect(cumulative.update(start, true), 12000L - i * 4000L)
        expect(cumulative.update(start + 4000, false), null)
    }
    expect(cumulative.update(400000, true), 0) // Visibility toggles never reset spent time.
    val backward = RendererValidationBudget(12000)
    expect(backward.update(1000, true), 12000)
    expect(backward.update(2000, true), 11000)
    expect(backward.update(1500, true), 11000)
    expect(backward.update(2000, true), 11000)
    expect(backward.update(Long.MAX_VALUE, true), 0) // Saturated accounting cannot overflow/replenish.
    println("RendererValidationBudgetChecks: $checks passed")
}
