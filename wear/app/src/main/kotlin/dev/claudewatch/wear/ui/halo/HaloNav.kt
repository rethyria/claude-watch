// Halo's navigation state machine: horizontal = pages (All, then one per
// project), vertical = depth (page → list → session feed), plus the
// approval/question card as an overlay flag. Pure Kotlin over HaloModel —
// no Compose, no I/O — so every transition is unit-testable, including the
// edge cases (no waiting items, a project vanishing under the user).
package dev.claudewatch.wear.ui.halo

/** Vertical position in the IA. Cards are an overlay, not a depth. */
enum class HaloDepth { PAGE, LIST, SESSION }

/** What a session list shows: everything, or one project's sessions. */
sealed interface ListScope {
    data object All : ListScope
    data class Project(val name: String) : ListScope
}

data class HaloNavState(
    /** Pager index: 0 = All, 1..n = model.projects[page - 1]. */
    val page: Int = 0,
    val depth: HaloDepth = HaloDepth.PAGE,
    val listScope: ListScope = ListScope.All,
    /** Session whose feed is open; only meaningful at [HaloDepth.SESSION]. */
    val sessionId: String? = null,
    /**
     * The approval/question card overlay. A flag rather than a depth because
     * the card floats over wherever it was summoned from, and closing it must
     * restore the exact prior position.
     */
    val cardOpen: Boolean = false,
    /**
     * The specific prompt the card was opened FOR (a project page's first
     * waiting item, a feed banner's own prompt). Null means the global queue
     * front. Rendering falls back to the front once this id leaves the queue —
     * that fallback IS the resolve-one-slide-in-the-next chaining (handoff §5).
     */
    val cardPermissionId: String? = null,
)

/** The list scope a given pager page drills into. */
fun scopeForPage(page: Int, model: HaloModel): ListScope =
    if (page <= 0) ListScope.All
    else model.projects.getOrNull(page - 1)?.let { ListScope.Project(it.name) } ?: ListScope.All

/** Swipe up on a page: into the All list or the current project's list. */
fun HaloNavState.drillToList(model: HaloModel): HaloNavState =
    copy(
        depth = HaloDepth.LIST,
        listScope = scopeForPage(page, model),
        sessionId = null,
        cardOpen = false,
        cardPermissionId = null,
    )

/** Tap a session row: into its live feed. */
fun HaloNavState.drillToSession(sessionId: String): HaloNavState =
    copy(depth = HaloDepth.SESSION, sessionId = sessionId, cardOpen = false, cardPermissionId = null)

/** Feed banner tap: raise the card for this session's own prompt. */
fun HaloNavState.openCard(permissionId: String?): HaloNavState =
    copy(cardOpen = true, cardPermissionId = permissionId)

/** Swipe down: card → feed → list → page; no-op at the top. */
fun HaloNavState.back(): HaloNavState = when {
    cardOpen -> copy(cardOpen = false, cardPermissionId = null)
    depth == HaloDepth.SESSION -> copy(depth = HaloDepth.LIST, sessionId = null)
    depth == HaloDepth.LIST -> copy(depth = HaloDepth.PAGE)
    else -> this
}

/** Tap the top TimeText (or resolve the last card): straight to All. */
fun HaloNavState.jumpHome(): HaloNavState = HaloNavState()

/**
 * Tap the centerpiece: open the first waiting item scoped to the current page
 * (page 0 = global queue order, a project page = that project's first waiting
 * session). The card opens OVER the session's feed so dismissing it lands
 * somewhere meaningful. No waiting items → no-op.
 */
fun HaloNavState.openFirstWaiting(model: HaloModel): HaloNavState {
    val scope = scopeForPage(page, model)
    val target = when (scope) {
        ListScope.All -> model.queue.firstOrNull()
        is ListScope.Project -> model.queue.firstOrNull { it.projectName == scope.name }
    } ?: return this
    return copy(
        depth = HaloDepth.SESSION,
        listScope = scope,
        sessionId = target.id,
        cardOpen = true,
        // Pin the card to THIS session's prompt: the global queue front can
        // belong to a different project than the page the user tapped.
        cardPermissionId = target.pending?.permissionId,
    )
}
