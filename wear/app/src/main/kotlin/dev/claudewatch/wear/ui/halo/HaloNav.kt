// Halo's navigation state machine: horizontal = pages (settings and usage left
// of home, All, then one per project), vertical = depth (page → list → session
// feed), plus the approval/question card as an overlay flag. Pure Kotlin over
// HaloModel — no Compose, no I/O — so every transition is unit-testable,
// including the edge cases (no waiting items, a project vanishing under the
// user, the depth-less settings/usage pages).
package dev.claudewatch.wear.ui.halo

/**
 * The usage page's [HaloNavState.page] value (issue #57). NEGATIVE by design:
 * All stays page 0, so [HaloNavState.jumpHome] and every existing depth/nav
 * path keeps landing on All untouched, and the page-to-project mapping
 * (`projects[page - 1]`) never shifts. The usage page is a glance surface
 * with NO depth below it: no drill-down, no centerpiece, no card summons.
 */
const val USAGE_PAGE = -1

/**
 * The settings page's [HaloNavState.page] value — LEFT of the usage page, so
 * MORE negative still. Same NEGATIVE design as [USAGE_PAGE]: it sits below page
 * 0, so All stays home, jumpHome is untouched, and `projects[page - 1]` never
 * shifts. Because it is `< 0` (and `<= 0`), the depth guards
 * ([drillToList]/[openFirstWaiting]/[scopeForPage]) fold it into the same
 * no-depth/All behavior as usage for free — settings is likewise a flat glance
 * surface with no drill-down, no centerpiece, no card summons.
 */
const val SETTINGS_PAGE = -2

/** Vertical position in the IA. Cards are an overlay, not a depth. */
enum class HaloDepth { PAGE, LIST, SESSION }

/** What a session list shows: everything, or one project's sessions. */
sealed interface ListScope {
    data object All : ListScope
    data class Project(val name: String) : ListScope
}

data class HaloNavState(
    /** Pager index: [SETTINGS_PAGE] = settings, [USAGE_PAGE] = usage, 0 = All, 1..n = model.projects[page - 1]. */
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

/**
 * The list scope a given pager page drills into. `<= 0` folds the settings
 * ([SETTINGS_PAGE]) and usage ([USAGE_PAGE]) pages into All: neither ever
 * drills itself (see [drillToList]), but any hand-built or stale state asking
 * must get a scope that exists rather than an index crash.
 */
fun scopeForPage(page: Int, model: HaloModel): ListScope =
    if (page <= 0) ListScope.All
    else model.projects.getOrNull(page - 1)?.let { ListScope.Project(it.name) } ?: ListScope.All

/**
 * Swipe up on a page: into the All list or the current project's list. The
 * settings and usage pages have no depth below them (issue #57) — both are
 * `< 0`, so a drill from either is a NO-OP, never a surprise jump into the All
 * list.
 */
fun HaloNavState.drillToList(model: HaloModel): HaloNavState {
    if (page < 0) return this
    return copy(
        depth = HaloDepth.LIST,
        listScope = scopeForPage(page, model),
        sessionId = null,
        cardOpen = false,
        cardPermissionId = null,
    )
}

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
 * somewhere meaningful. No waiting items → no-op. The settings and usage pages
 * render no centerpiece (issue #57), so this is a no-op from either too (both
 * `< 0`) — the state machine must not hide a depth jump behind a page that
 * offers no tap target.
 */
fun HaloNavState.openFirstWaiting(model: HaloModel): HaloNavState {
    if (page < 0) return this
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
