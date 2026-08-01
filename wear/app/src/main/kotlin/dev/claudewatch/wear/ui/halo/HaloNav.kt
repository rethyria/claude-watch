// Halo's navigation state machine: horizontal = pages (settings and usage left
// of home, All, then one per project), vertical = depth (page → list pager →
// session feed), plus the approval/question card as an overlay flag. Pure
// Kotlin over HaloModel — no Compose, no I/O — so every transition is
// unit-testable, including the edge cases (no waiting items, a project
// vanishing under the user, the depth-less settings/usage pages, the pager's
// spawn slot and empty scopes).
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

/**
 * The ordered sessions a list scope shows — the ONE source of session order
 * for the pager, the ring, and the self-heal. All is the model's flat list
 * (itself the project flatten by construction, so ring order == pager order);
 * a project is exactly its own group; a scope whose project vanished under
 * the user degrades to empty rather than crashing.
 */
fun HaloModel.sessionsIn(scope: ListScope): List<HaloSession> = when (scope) {
    ListScope.All -> sessions
    is ListScope.Project -> projects.firstOrNull { it.name == scope.name }?.sessions.orEmpty()
}

data class HaloNavState(
    /** Pager index: [SETTINGS_PAGE] = settings, [USAGE_PAGE] = usage, 0 = All, 1..n = model.projects[page - 1]. */
    val page: Int = 0,
    val depth: HaloDepth = HaloDepth.PAGE,
    val listScope: ListScope = ListScope.All,
    /**
     * The selected session. At [HaloDepth.SESSION] it is the open feed; at
     * [HaloDepth.LIST] it is the pager's selected card — null there means the
     * trailing spawn card (All scope) or an empty scope, not "nothing". Always
     * null at [HaloDepth.PAGE]: a page has no selection, and a stale id would
     * leak into the next drill's keep-if-in-scope resolution.
     */
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
 * Swipe up on a page: into the All list or the current project's list, with
 * the pager selection resolved up front — keep the current session if it is
 * in scope, else the scope's first, else null (the spawn card alone, or an
 * empty scope). The settings and usage pages have no depth below them (issue
 * #57) — both are `< 0`, so a drill from either is a NO-OP, never a surprise
 * jump into the All list.
 */
fun HaloNavState.drillToList(model: HaloModel): HaloNavState {
    if (page < 0) return this
    val scope = scopeForPage(page, model)
    val inScope = model.sessionsIn(scope)
    return copy(
        depth = HaloDepth.LIST,
        listScope = scope,
        sessionId = sessionId.takeIf { id -> inScope.any { it.id == id } }
            ?: inScope.firstOrNull()?.id,
        cardOpen = false,
        cardPermissionId = null,
    )
}

/**
 * Swipe on the list pager: move the selection by [delta] slots (+1 next, −1
 * previous), NO wrap — both ends are hard stops, so the ring highlight never
 * teleports across midnight. All scope appends ONE trailing spawn slot
 * (`sessionId = null`) as the true end: last session → spawn card → no-op. A
 * project scope ends at its last session. Only meaningful at LIST depth, and
 * a no-op when the selection already vanished under us — repairing that is
 * the self-heal's job, not a swipe's.
 */
fun HaloNavState.step(delta: Int, model: HaloModel): HaloNavState {
    if (depth != HaloDepth.LIST) return this
    val slots: List<String?> = model.sessionsIn(listScope).map { it.id } +
        if (listScope == ListScope.All) listOf(null) else emptyList()
    val at = slots.indexOf(sessionId)
    if (at < 0) return this
    val to = at + delta
    if (to !in slots.indices) return this
    return copy(sessionId = slots[to])
}

/**
 * Horizontal swipe or dot-adjacent step on a MAIN page: move by [delta] pages
 * with hard stops at both ends — [SETTINGS_PAGE] on the left, the last
 * project on the right. The nav machine owns the page since the v2 shell
 * dropped HorizontalPager (epic #94: no drag-follow), so the bounds the
 * pager's pageCount used to enforce live here, unit-tested. Only meaningful
 * at PAGE depth: deeper levels own their own horizontal gestures.
 */
fun HaloNavState.stepPage(delta: Int, model: HaloModel): HaloNavState {
    if (depth != HaloDepth.PAGE) return this
    return copy(page = (page + delta).coerceIn(SETTINGS_PAGE, model.projects.size))
}

/**
 * True on the FIRST pager slot of the current scope: there the UI maps
 * swipe-right/‹ to [back] instead of a step. With sessions present the spawn
 * card is the END, so a null selection is NOT the start; an empty scope's
 * sole slot (spawn card, or nothing) is trivially both — back must work.
 */
fun HaloNavState.atListStart(model: HaloModel): Boolean =
    sessionId == model.sessionsIn(listScope).firstOrNull()?.id

/**
 * The LIST-depth self-heal (Halo v2 S5): repairs a selection that vanished
 * under the cursor. [step]/[atListStart] deliberately dead-end on a stale id
 * (a swipe must not guess) and [back] from a vanished feed parks the dead id
 * as the LIST selection — so the pager, the one reader of the LIST selection,
 * heals it before rendering. [rememberedIndex] is the slot the selection LAST
 * resolved to (tracked by the UI): the session now at that index is the dead
 * one's next-door neighbour, clamped to the last slot when the end was killed.
 * An emptied All scope lands on the spawn card (the sole remaining slot); an
 * emptied project scope has NO slots at all — its project just vanished from
 * the model — so it backs all the way out. In-scope selections, other depths
 * and the spawn card itself pass through untouched.
 */
fun HaloNavState.healListSelection(model: HaloModel, rememberedIndex: Int): HaloNavState {
    if (depth != HaloDepth.LIST || sessionId == null) return this
    val inScope = model.sessionsIn(listScope)
    if (inScope.any { it.id == sessionId }) return this
    return when {
        inScope.isNotEmpty() ->
            copy(sessionId = inScope[rememberedIndex.coerceIn(0, inScope.size - 1)].id)
        listScope == ListScope.All -> copy(sessionId = null)
        else -> jumpHome()
    }
}

/** Tap a session row: into its live feed. */
fun HaloNavState.drillToSession(sessionId: String): HaloNavState =
    copy(depth = HaloDepth.SESSION, sessionId = sessionId, cardOpen = false, cardPermissionId = null)

/** Feed banner tap: raise the card for this session's own prompt. */
fun HaloNavState.openCard(permissionId: String?): HaloNavState =
    copy(cardOpen = true, cardPermissionId = permissionId)

/**
 * The pager card's Answer pill: raise the card OVER the list — depth stays
 * LIST — pinned to THIS session's own prompt, so "decide later" lands right
 * back on the same pager card (unlike a feed's [openCard], there is no feed
 * underneath to land in). No pending prompt → no-op: a null pin would render
 * the global queue front, floating some OTHER session's prompt over this card.
 */
fun HaloNavState.openCardForListSession(session: HaloSession): HaloNavState {
    val pending = session.pending ?: return this
    return copy(sessionId = session.id, cardOpen = true, cardPermissionId = pending.permissionId)
}

/** Swipe down: card → feed → list → page; no-op at the top. */
fun HaloNavState.back(): HaloNavState = when {
    cardOpen -> copy(cardOpen = false, cardPermissionId = null)
    // Feed → list KEEPS the session: it becomes the pager selection, and the
    // shrink morph must land on that session's ring segment, not the first's.
    depth == HaloDepth.SESSION -> copy(depth = HaloDepth.LIST)
    depth == HaloDepth.LIST -> copy(depth = HaloDepth.PAGE, sessionId = null)
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

/**
 * What the system back gesture/button should do, for [systemBack]. A third
 * outcome exists beyond "new nav state": the overlays that float OUTSIDE the
 * nav machine (the voice overlay, the spawn picker — plain flags in HaloApp,
 * deliberately not nav depths) outrank every nav move, and dismissing one is
 * the caller's side effect, not a state this file can return.
 */
sealed interface SystemBack {
    /** Dismiss the topmost open overlay; the caller owns those flags. */
    data object DismissOverlay : SystemBack

    /** Route into the nav machine: land on [nav]. */
    data class Navigate(val nav: HaloNavState) : SystemBack
}

/**
 * The system back gesture's ONE route through the IA (issue #109): the root
 * PredictiveBackHandler routes every gesture completion through this, so the
 * priority order is pinned here on the JVM instead of living in a gesture
 * callback. Overlay first, then the card (closing it restores the exact
 * prior position — [back]'s card branch), then one depth step (feed → list
 * keeps the selection, list → page clears it), then any non-home page —
 * projects, usage, settings — jumps straight home rather than walking the
 * row. Null means HOME AT REST: nothing left to route, and the handler —
 * which stays registered even here (round 2's always-enabled invariant: an
 * enabled flag that drops mid-gesture lets the system commit its own back)
 * — finishes the activity itself, the one deliberate exit.
 */
fun systemBack(nav: HaloNavState, overlayOpen: Boolean): SystemBack? = when {
    overlayOpen -> SystemBack.DismissOverlay
    nav.cardOpen -> SystemBack.Navigate(nav.back())
    nav.depth != HaloDepth.PAGE -> SystemBack.Navigate(nav.back())
    nav.page != 0 -> SystemBack.Navigate(nav.jumpHome())
    else -> null
}
