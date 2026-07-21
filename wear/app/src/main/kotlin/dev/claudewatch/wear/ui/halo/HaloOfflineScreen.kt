// Handoff §8 (offline / re-pair). The spec's per-second "retry in Ns"
// countdown renders as the engine's own status line instead ("paired,
// reconnecting (reason)") — UiState does not expose the backoff deadline,
// and the UI layer does not reach into the engine to get one. The pairing
// PATH itself lives here: this screen is the whole unpaired/offline
// experience and MainActivity mounts HaloApp directly, so without a pairing
// affordance a fresh install would dead-end on "not paired" with nothing
// tappable.
//
// Two coexisting paths (issue #23 follow-up): MANUAL keeps host/port/code
// entry unchanged (the code stays — for a bridge on a different LAN, or when
// discovery can't run). DISCOVER is code-less: an NSD scan presents a LIST of
// found bridges, and tapping one pairs with NO code — the bridge's
// operator-opened pairing window is the whole gate. A Choose pane fronts both.
// Pairing goes straight through onPair / onPairByDiscovery — the engine
// re-pairs in place, so re-pairing never requires unpair (which wipes
// credentials) first.
package dev.claudewatch.wear.ui.halo

import android.app.Activity
import android.app.RemoteInput
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import androidx.wear.input.RemoteInputIntentHelper
import dev.claudewatch.wear.BridgeViewModel
import dev.claudewatch.wear.net.BridgeDiscovery

/** Which sub-screen the offline pairing flow is showing. */
private enum class Pane { Choose, Manual, Discover }

@Composable
fun HaloOfflineScreen(
    ui: BridgeViewModel.UiState,
    onPair: (host: String, port: String, code: String) -> Unit,
    modifier: Modifier = Modifier,
    onDiscoverForPairing: () -> Unit = {},
    onDiscoverBridges: () -> Unit = {},
    onPairByDiscovery: (BridgeDiscovery.DiscoveredBridge) -> Unit = {},
) {
    var host by remember { mutableStateOf("10.0.2.2") }
    var port by remember { mutableStateOf("7860") }
    var code by remember { mutableStateOf("") }
    // Choose is the entry pane when unpaired. Paired-but-offline (the engine is
    // retrying on its own) hides the chooser behind a quiet "re-pair watch"
    // chip so it doesn't shout over "reconnecting".
    var pane by remember { mutableStateOf(Pane.Choose) }
    var revealed by remember { mutableStateOf(false) }
    val showChooser = !ui.paired || revealed

    // Issue #23 zero-typing: the Manual form pre-fills host/port from a single
    // NSD hit. Fired ONLY when the Manual pane is showing (keyed on `pane`) so
    // this scan never races the Discover-list scan over the shared single-flight
    // Wi-Fi bind. The fields keep their manual defaults as a fallback
    // (emulator/no-bridge never lands a discovery), and seeding is one-shot per
    // discovered value — a later manual edit is never clobbered because the
    // LaunchedEffect only re-fires when the discovered value itself changes.
    LaunchedEffect(pane) { if (pane == Pane.Manual) onDiscoverForPairing() }
    LaunchedEffect(ui.discoveredHost) { ui.discoveredHost?.let { host = it } }
    LaunchedEffect(ui.discoveredPort) { ui.discoveredPort?.let { port = it.toString() } }

    Box(modifier = modifier.fillMaxSize()) {
        // The Discover LIST is a full-screen ScalingLazyColumn that owns the
        // whole surface; every other pane is the centered ring layout.
        if (showChooser && pane == Pane.Discover && ui.discover is BridgeViewModel.DiscoverUi.Found) {
            DiscoveredBridgeList(
                bridges = ui.discover.bridges,
                onSelect = onPairByDiscovery,
                onBack = { pane = Pane.Choose },
            )
            return@Box
        }

        HaloRing(states = emptyList(), drained = true)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Halo.Geo.SafeInset),
        ) {
            when {
                // Paired-but-offline, chooser not yet revealed: reconnecting
                // headline + the quiet re-pair chip.
                !showChooser -> {
                    OfflineHeadline(paired = true, ui = ui)
                    Spacer(Modifier.height(8.dp))
                    Chip(
                        onClick = { revealed = true; pane = Pane.Choose },
                        label = {
                            Text(
                                "re-pair watch",
                                fontSize = Halo.Type.Caption,
                                color = Halo.Palette.WaitingForYou,
                            )
                        },
                        colors = ChipDefaults.secondaryChipColors(
                            backgroundColor = Halo.Palette.Surface,
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("repairButton"),
                    )
                }

                pane == Pane.Choose -> {
                    OfflineHeadline(paired = ui.paired, ui = ui)
                    Spacer(Modifier.height(8.dp))
                    Chip(
                        onClick = { pane = Pane.Manual },
                        label = {
                            Text("Manual", fontSize = Halo.Type.Caption, color = Halo.Palette.ApproveText)
                        },
                        colors = ChipDefaults.primaryChipColors(
                            backgroundColor = Halo.Palette.WaitingForYou,
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("manualButton"),
                    )
                    Spacer(Modifier.height(6.dp))
                    Chip(
                        onClick = { pane = Pane.Discover; onDiscoverBridges() },
                        label = {
                            Text("Discover", fontSize = Halo.Type.Caption, color = Halo.Palette.TextPrimary)
                        },
                        colors = ChipDefaults.secondaryChipColors(
                            backgroundColor = Halo.Palette.Surface,
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("discoverButton"),
                    )
                }

                pane == Pane.Manual -> {
                    OfflineHeadline(paired = ui.paired, ui = ui)
                    Spacer(Modifier.height(8.dp))
                    PairField("host", host, "host") { host = it }
                    PairField("port", port, "port") { port = it }
                    PairField("code", code, "code") { code = it }
                    Chip(
                        onClick = { onPair(host.trim(), port.trim(), code.trim()) },
                        label = {
                            Text("pair", fontSize = Halo.Type.Caption, color = Halo.Palette.ApproveText)
                        },
                        colors = ChipDefaults.primaryChipColors(
                            backgroundColor = Halo.Palette.WaitingForYou,
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("pairButton"),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "← back",
                        fontSize = Halo.Type.Min,
                        color = Halo.Palette.TextFaint,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { pane = Pane.Choose }
                            .testTag("manualBack")
                            .padding(vertical = 6.dp),
                    )
                }

                // pane == Pane.Discover, non-list states (Idle/Scanning/Empty/
                // PairError). The Found list is handled full-screen above.
                else -> DiscoverStatusPane(
                    discover = ui.discover,
                    onScanAgain = onDiscoverBridges,
                    onBack = { pane = Pane.Choose },
                )
            }
        }
    }
}

/** The unpaired/offline headline + status line (testTag "status" is what the
 *  WalkingSkeleton gates the online transition on). */
@Composable
private fun OfflineHeadline(paired: Boolean, ui: BridgeViewModel.UiState) {
    Text(
        text = if (paired) "bridge offline" else "not paired",
        fontSize = Halo.Type.Title,
        color = Halo.Palette.Error,
        textAlign = TextAlign.Center,
    )
    Text(
        text = ui.repairExplanation ?: ui.status,
        fontSize = Halo.Type.Min,
        color = Halo.Palette.TextFaint,
        textAlign = TextAlign.Center,
        maxLines = 3,
        modifier = Modifier.testTag("status"),
    )
}

/** Discover pane's non-list states: scanning, empty, or a per-bridge pair error. */
@Composable
private fun DiscoverStatusPane(
    discover: BridgeViewModel.DiscoverUi,
    onScanAgain: () -> Unit,
    onBack: () -> Unit,
) {
    Text(
        text = "discover bridges",
        fontSize = Halo.Type.Title,
        color = Halo.Palette.TextPrimary,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    when (discover) {
        is BridgeViewModel.DiscoverUi.Scanning ->
            Text(
                text = "scanning…",
                fontSize = Halo.Type.Body,
                color = Halo.Palette.WaitingForYou,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag("discoverScanning"),
            )

        is BridgeViewModel.DiscoverUi.Empty ->
            Text(
                text = "No bridges found. Is the bridge running and on this Wi-Fi?",
                fontSize = Halo.Type.Min,
                color = Halo.Palette.TextFaint,
                textAlign = TextAlign.Center,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth().testTag("discoverEmpty"),
            )

        is BridgeViewModel.DiscoverUi.PairError ->
            Text(
                text = discover.message,
                fontSize = Halo.Type.Min,
                color = Halo.Palette.Error,
                textAlign = TextAlign.Center,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth().testTag("discoverPairError"),
            )

        // Idle (and the Found case, which never reaches here — it renders as
        // the full-screen list): show nothing but the controls below.
        else -> Unit
    }
    Spacer(Modifier.height(8.dp))
    // "scan again" is offered only when a scan is NOT in flight. While
    // "scanning…" shows, re-triggering would race the live scan over
    // NsdBridgeDiscovery's process-global Wi-Fi single-flight; the loser returns
    // an immediate empty list, flipping the pane to a false "no bridges found"
    // (see BridgeViewModel.discoverBridgesForPairing). During a scan the pane
    // shows only the status line + the back affordance — nothing to re-fire.
    if (discover !is BridgeViewModel.DiscoverUi.Scanning) {
        Chip(
            onClick = onScanAgain,
            label = {
                Text("scan again", fontSize = Halo.Type.Caption, color = Halo.Palette.ApproveText)
            },
            colors = ChipDefaults.primaryChipColors(backgroundColor = Halo.Palette.WaitingForYou),
            modifier = Modifier.fillMaxWidth().testTag("discoverScanAgain"),
        )
        Spacer(Modifier.height(6.dp))
    }
    Text(
        text = "← back",
        fontSize = Halo.Type.Min,
        color = Halo.Palette.TextFaint,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onBack)
            .testTag("discoverBack")
            .padding(vertical = 6.dp),
    )
}

/** Cancel-swipe threshold ≈60px at the 450 reference, matching HaloSpawnPicker. */
private val LIST_BACK_SWIPE_THRESHOLD = 30.dp

/**
 * The discovered bridges, one TouchMin row each (machineName + host:port).
 * Full-screen ScalingLazyColumn — the HaloSpawnPicker idiom: rotary bezel
 * scroll, AutoCentering, and the API 31+ stretch-overscroll disabled (else the
 * stretch eats the pull-past-threshold "back" gesture). NO consuming
 * pointerInput anywhere (the device-bisected real-touch trap — HaloApp.kt).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiscoveredBridgeList(
    bridges: List<BridgeDiscovery.DiscoveredBridge>,
    onSelect: (BridgeDiscovery.DiscoveredBridge) -> Unit,
    onBack: () -> Unit,
) {
    // Center the FIRST BRIDGE ROW (index 1), not the header (index 0), on entry.
    // Item 0 is the "select a bridge" caption; centering it (the HaloSpawnPicker
    // default) pushes the first real, tappable row into the lower half of the
    // round screen, so the user has to scroll UP to reach it. Found always
    // carries ≥1 bridge (BridgeViewModel: empty → DiscoverUi.Empty), so index 1
    // always exists; autoCentering stays on item 0 so the header can still be
    // pulled to center and the top padding is unchanged.
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 1)
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Swipe-down-back from the drags the list rejects at its top — the same
    // pull-past-threshold contract HaloSpawnPicker's cancel uses.
    val currentOnBack by rememberUpdatedState(onBack)
    val backThresholdPx = with(LocalDensity.current) { LIST_BACK_SWIPE_THRESHOLD.toPx() }
    val backConnection = remember(listState, backThresholdPx) {
        object : NestedScrollConnection {
            private var pulled = 0f
            private var fired = false

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                if (available.y > 0f && !listState.canScrollBackward) {
                    pulled += available.y
                    if (!fired && pulled > backThresholdPx) {
                        fired = true
                        currentOnBack()
                    }
                } else if (consumed.y != 0f || available.y < 0f) {
                    pulled = 0f
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                pulled = 0f
                fired = false
                return Velocity.Zero
            }
        }
    }

    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
        ScalingLazyColumn(
            state = listState,
            autoCentering = AutoCenteringParams(itemIndex = 0),
            verticalArrangement = Arrangement.spacedBy(5.dp),
            contentPadding = PaddingValues(horizontal = Halo.Geo.SafeInset),
            modifier = Modifier
                .fillMaxSize()
                .background(Halo.Palette.Background)
                .nestedScroll(backConnection)
                .rotaryScrollable(
                    behavior = RotaryScrollableDefaults.behavior(listState),
                    focusRequester = focusRequester,
                ),
        ) {
            item {
                Text(
                    text = "select a bridge",
                    fontSize = Halo.Type.Caption,
                    color = Halo.Palette.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp),
                )
            }
            bridges.forEach { bridge ->
                item(key = "bridge:${bridge.bridgeId}") {
                    DiscoveredBridgeRow(
                        title = bridge.machineName,
                        subtitle = "${bridge.hostIp}:${bridge.port}",
                        tag = "discoverBridge-${bridge.bridgeId}",
                        onSelect = { onSelect(bridge) },
                    )
                }
            }
            item {
                Text(
                    text = "↓ back",
                    fontSize = Halo.Type.Min,
                    color = Halo.Palette.TextFaint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }
}

/** One discovered bridge as a quiet pill (same geometry family as the session rows). */
@Composable
private fun DiscoveredBridgeRow(
    title: String,
    subtitle: String,
    tag: String,
    onSelect: () -> Unit,
) {
    val shape = RoundedCornerShape(Halo.Geo.RowRadius)
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Halo.Geo.TouchMin)
            .background(Halo.Palette.Surface, shape)
            .clickable(onClick = onSelect)
            .testTag(tag)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = title,
            fontSize = Halo.Type.Title,
            fontWeight = FontWeight.Medium,
            color = Halo.Palette.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            fontSize = Halo.Type.Min,
            color = Halo.Palette.TextFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * One pairing field. NOT an inline Compose text field: on the watch those
 * cannot reliably drive the system IME — the Samsung keyboard streams input
 * through the composing region, which the Compose BasicTextField interop
 * drops on this device (the caret moves but the glyphs never stick), so a
 * real finger could tap and type and end up with an empty field. Instead the
 * field is a tappable row that launches the Wear RemoteInput activity — the
 * platform's own full-screen input surface (keyboard + voice) — which hands
 * the finished string back as an activity result. No inline editing, no
 * composing-region interop, so no keyboard can corrupt it mid-type; this is
 * the input path Wear itself recommends over embedded text fields. The tag
 * stays on the tappable row so the instrumented pairing leg can still find
 * it (it drives the value through a test seam, not the real IME — the IME
 * cannot run headless anyway).
 */
@Composable
private fun PairField(label: String, value: String, tag: String, onChange: (String) -> Unit) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            RemoteInput.getResultsFromIntent(result.data)
                ?.getCharSequence(REMOTE_INPUT_KEY)
                ?.toString()
                ?.let { onChange(it.trim()) }
        }
    }
    Text(label, fontSize = Halo.Type.Min, color = Halo.Palette.TextFaint)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Halo.Palette.Surface, RoundedCornerShape(8.dp))
            .clickable {
                // The instrumented pairing leg cannot drive the Wear
                // RemoteInput activity (no headless IME) and the field is no
                // longer an inline node performTextInput can fill, so a test
                // seam short-circuits the tap to a canned value (see
                // PairFieldInput). Production leaves the seam null and takes
                // the real RemoteInput path — the only path a finger takes.
                val seam = PairFieldInput.override
                if (seam != null) {
                    seam(label)?.let { onChange(it.trim()) }
                    return@clickable
                }
                val remoteInput = RemoteInput.Builder(REMOTE_INPUT_KEY)
                    .setLabel(label)
                    .build()
                val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
                RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(remoteInput))
                launcher.launch(intent)
            }
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .testTag(tag),
    ) {
        Text(
            text = value.ifEmpty { "tap to enter" },
            fontSize = Halo.Type.Body,
            color = if (value.isEmpty()) Halo.Palette.TextFaint else Halo.Palette.TextPrimary,
        )
    }
    Spacer(Modifier.height(4.dp))
}

private const val REMOTE_INPUT_KEY = "pair_field_value"

/**
 * Test seam for [PairField] (the resolver-seam idiom used by
 * BridgeSessionService/GlanceStateSource). When [override] is non-null,
 * tapping a pairing field routes to it — returning the string to fill, or
 * null to leave the field unchanged (a cancelled input) — instead of
 * launching the Wear RemoteInput activity, which needs a real IME and does
 * not exist in the instrumented harness. Production never sets it, so a real
 * finger always gets the real system input surface.
 */
internal object PairFieldInput {
    @Volatile
    internal var override: ((label: String) -> String?)? = null
}
