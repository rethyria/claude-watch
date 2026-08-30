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
//
// Issue #29 rides the Discover path: on a platform that gates LAN access
// behind the ACCESS_LOCAL_NETWORK runtime permission (API 37+ — see
// net/LocalNetworkPermission.kt for why 36 is out), the Discover pane fronts
// the scan with an on-screen rationale + the system ask, and a denial gets an
// honest explanation with a settings route — never a silent empty scan.
// Manual pairing stays reachable throughout (a non-LAN-routed bridge still
// works without the grant). On every watch that exists today the gate is
// inert and this screen behaves exactly as before.
package dev.claudewatch.wear.ui.halo

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import dev.claudewatch.wear.BridgeViewModel
import dev.claudewatch.wear.net.BridgeDiscovery
import dev.claudewatch.wear.net.LocalNetworkPermission

/**
 * Which sub-screen the offline pairing flow is showing. Hoisted to the Halo
 * root together with the paired-offline `revealed` flag (issue #127): the
 * takeover is MODAL, so the system back must address ITS hierarchy — the
 * root back handler owns the one-step walk (sub-pane → Choose → the quiet
 * reconnecting headline) and needs the state to make it.
 */
internal enum class OfflinePane { Choose, Manual, Discover }

@Composable
internal fun HaloOfflineScreen(
    ui: BridgeViewModel.UiState,
    onPair: (host: String, port: String, code: String) -> Unit,
    modifier: Modifier = Modifier,
    onDiscoverForPairing: () -> Unit = {},
    onDiscoverBridges: () -> Unit = {},
    onPairByDiscovery: (BridgeDiscovery.DiscoveredBridge) -> Unit = {},
    // Choose is the entry pane when unpaired. Paired-but-offline (the engine
    // is retrying on its own) hides the chooser behind a quiet "re-pair
    // watch" chip ([revealed]) so it doesn't shout over "reconnecting". Both
    // hoisted (issue #127) — the root back handler walks them; this screen
    // only reports taps.
    pane: OfflinePane = OfflinePane.Choose,
    onPane: (OfflinePane) -> Unit = {},
    revealed: Boolean = false,
    onReveal: () -> Unit = {},
) {
    var host by remember { mutableStateOf("10.0.2.2") }
    var port by remember { mutableStateOf("7860") }
    var code by remember { mutableStateOf("") }
    // Which pairing field the digit pad is editing (null = no pad). Local, not
    // hoisted with `pane` (#127): the pad is a leaf of the Manual pane, and its
    // own BackHandler dismisses it before the root back-walk runs. Cleared when
    // the pane changes underneath it (a root back-walk to Choose) so Manual
    // never re-opens onto a stale pad.
    var editing by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pane) { if (pane != OfflinePane.Manual) editing = null }
    val showChooser = !ui.paired || revealed

    // Issue #29: the local-network gate. Inert on every current watch
    // (needsRequest is false below API 37 AND while the grant is held — this
    // targetSdk holds it implicitly), so all of today's flows are untouched.
    // grantProbe re-reads the grant after the two events that can change it
    // outside composition: the request round-tripping, and a settings visit
    // (ON_RESUME below — a deep-link grant changes no compose state on its
    // own, so without the probe the gate pane would sit stale until killed).
    val context = LocalContext.current
    var grantProbe by remember { mutableStateOf(0) }
    val needsLocalNetworkGrant =
        remember(grantProbe) { LocalNetworkPermission.needsRequest(context) }
    var localNetworkDenied by remember { mutableStateOf(false) }
    val localNetworkRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        grantProbe++
        localNetworkDenied = !isGranted
        // The grant is the go signal for the scan the rationale chip promised.
        if (isGranted) onDiscoverBridges()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) grantProbe++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Issue #23 zero-typing: the Manual form pre-fills host/port from a single
    // NSD hit. Fired ONLY when the Manual pane is showing (keyed on `pane`) so
    // this scan never races the Discover-list scan over the shared single-flight
    // Wi-Fi bind. The fields keep their manual defaults as a fallback
    // (emulator/no-bridge never lands a discovery), and seeding is one-shot per
    // discovered value — a later manual edit is never clobbered because the
    // LaunchedEffect only re-fires when the discovered value itself changes.
    // Under the #29 gate the pre-fill is silently skipped, not asked for: the
    // user chose typing, and the scan is an optimisation the platform would
    // EPERM-block anyway (a mid-pane grant re-keys the effect and lands it).
    LaunchedEffect(pane, needsLocalNetworkGrant) {
        if (pane == OfflinePane.Manual && !needsLocalNetworkGrant) onDiscoverForPairing()
    }
    LaunchedEffect(ui.discoveredHost) { ui.discoveredHost?.let { host = it } }
    LaunchedEffect(ui.discoveredPort) { ui.discoveredPort?.let { port = it.toString() } }

    Box(modifier = modifier.fillMaxSize()) {
        // The Discover LIST is a full-screen ScalingLazyColumn that owns the
        // whole surface; every other pane is the centered ring layout. Gated
        // like the scans (#29): a Found list left over from before a
        // settings-revoke would offer taps whose pair POSTs the platform now
        // blocks — the gate pane's explanation is the honest surface instead.
        if (showChooser && pane == OfflinePane.Discover && !needsLocalNetworkGrant &&
            ui.discover is BridgeViewModel.DiscoverUi.Found
        ) {
            DiscoveredBridgeList(
                bridges = ui.discover.bridges,
                onSelect = onPairByDiscovery,
                onBack = { onPane(OfflinePane.Choose) },
            )
            return@Box
        }

        // The digit pad owns the whole surface while a pairing field is being
        // edited — same full-screen takeover the Discover list gets.
        val editingField = editing
        if (editingField != null && showChooser && pane == OfflinePane.Manual) {
            DigitPadPane(
                label = editingField,
                initial = when (editingField) {
                    "host" -> host
                    "port" -> port
                    else -> code
                },
                allowDot = editingField == "host",
                maxLen = when (editingField) {
                    "host" -> 15 // xxx.xxx.xxx.xxx
                    "port" -> 5
                    else -> 6
                },
                onDone = { entered ->
                    when (editingField) {
                        "host" -> host = entered
                        "port" -> port = entered
                        else -> code = entered
                    }
                    editing = null
                },
                onCancel = { editing = null },
            )
            return@Box
        }

        HaloDrainedRing()
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
                        onClick = { onReveal(); onPane(OfflinePane.Choose) },
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

                pane == OfflinePane.Choose -> {
                    OfflineHeadline(paired = ui.paired, ui = ui)
                    Spacer(Modifier.height(8.dp))
                    Chip(
                        onClick = { onPane(OfflinePane.Manual) },
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
                        // Under the #29 gate the tap opens the Discover pane
                        // WITHOUT scanning — the gate pane's rationale + ask
                        // stand where the scan states would; the grant callback
                        // fires the scan the moment it lands.
                        onClick = {
                            onPane(OfflinePane.Discover)
                            if (!needsLocalNetworkGrant) onDiscoverBridges()
                        },
                        label = {
                            Text("Discover", fontSize = Halo.Type.Caption, color = Halo.Palette.TextPrimary)
                        },
                        colors = ChipDefaults.secondaryChipColors(
                            backgroundColor = Halo.Palette.Surface,
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("discoverButton"),
                    )
                }

                pane == OfflinePane.Manual -> {
                    OfflineHeadline(paired = ui.paired, ui = ui)
                    Spacer(Modifier.height(8.dp))
                    PairField("host", host, "host", onEdit = { editing = "host" }) { host = it }
                    PairField("port", port, "port", onEdit = { editing = "port" }) { port = it }
                    PairField("code", code, "code", onEdit = { editing = "code" }) { code = it }
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
                        color = Halo.Palette.TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPane(OfflinePane.Choose) }
                            .testTag("manualBack")
                            .padding(vertical = 6.dp),
                    )
                }

                // pane == OfflinePane.Discover with the #29 gate up: the
                // rationale/denial pane owns the surface INSTEAD of the scan
                // states — no scan ran, and none will until the grant lands.
                needsLocalNetworkGrant -> LocalNetworkGatePane(
                    denied = localNetworkDenied,
                    onAllow = { localNetworkRequest.launch(LocalNetworkPermission.PERMISSION) },
                    onOpenSettings = {
                        // App-details is the one settings surface every Wear
                        // image ships; runCatching keeps an image without it
                        // at a no-op, never a crash on the denial path.
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                ),
                            )
                        }
                    },
                    onBack = { onPane(OfflinePane.Choose) },
                )

                // pane == OfflinePane.Discover, non-list states (Idle/Scanning/
                // Empty/PairError). The Found list is handled full-screen above.
                else -> DiscoverStatusPane(
                    discover = ui.discover,
                    onScanAgain = onDiscoverBridges,
                    onBack = { onPane(OfflinePane.Choose) },
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
        color = Halo.Palette.TextSecondary,
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
                color = Halo.Palette.TextSecondary,
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
        color = Halo.Palette.TextSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onBack)
            .testTag("discoverBack")
            .padding(vertical = 6.dp),
    )
}

/**
 * The #29 local-network gate as the Discover pane's content: the on-screen
 * rationale + the ask BEFORE any scan (the system dialog carries no app text,
 * so the reason must already be on screen while the user decides), or — after
 * a denial — an honest explanation with a settings route. Never rendered on a
 * platform that doesn't gate LAN access, and manual pairing stays one back-tap
 * away in both states, so a denial is a reduced mode, not a dead app.
 */
@Composable
private fun LocalNetworkGatePane(
    denied: Boolean,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    Text(
        text = "discover bridges",
        fontSize = Halo.Type.Title,
        color = Halo.Palette.TextPrimary,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    if (!denied) {
        Text(
            text = "Finding your bridge scans this Wi-Fi for nearby devices — Android asks first.",
            fontSize = Halo.Type.Min,
            color = Halo.Palette.TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 5,
            modifier = Modifier.fillMaxWidth().testTag("localNetRationale"),
        )
        Spacer(Modifier.height(8.dp))
        Chip(
            onClick = onAllow,
            label = {
                Text(
                    "allow local network",
                    fontSize = Halo.Type.Caption,
                    color = Halo.Palette.ApproveText,
                )
            },
            colors = ChipDefaults.primaryChipColors(
                backgroundColor = Halo.Palette.WaitingForYou,
            ),
            modifier = Modifier.fillMaxWidth().testTag("localNetAllow"),
        )
    } else {
        Text(
            text = "Local network access is denied, so scanning can't run. " +
                "Allow it in settings — or pair manually.",
            fontSize = Halo.Type.Min,
            color = Halo.Palette.Error,
            textAlign = TextAlign.Center,
            maxLines = 5,
            modifier = Modifier.fillMaxWidth().testTag("localNetDenied"),
        )
        Spacer(Modifier.height(8.dp))
        Chip(
            onClick = onOpenSettings,
            label = {
                Text(
                    "open settings",
                    fontSize = Halo.Type.Caption,
                    color = Halo.Palette.ApproveText,
                )
            },
            colors = ChipDefaults.primaryChipColors(
                backgroundColor = Halo.Palette.WaitingForYou,
            ),
            modifier = Modifier.fillMaxWidth().testTag("localNetSettings"),
        )
    }
    Spacer(Modifier.height(6.dp))
    Text(
        text = "← back",
        fontSize = Halo.Type.Min,
        color = Halo.Palette.TextSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onBack)
            .testTag("discoverBack")
            .padding(vertical = 6.dp),
    )
}

/** Cancel-swipe threshold ≈60px at the 450 reference, matching HaloSpawnPicker. */
/**
 * The discovered bridges, one TouchMin row each (machineName + host:port).
 * Full-screen ScalingLazyColumn — the HaloSpawnPicker idiom: rotary bezel
 * scroll, top-anchored content (autoCentering off — see listState), and the API
 * 31+ stretch-overscroll disabled (else the stretch eats the pull-from-the-top
 * back). Back is a pull-down from the resting top bound (rememberAtTopBack-
 * Connection): only fires if the list was at the top as the gesture began. NO
 * consuming pointerInput anywhere (the device-bisected real-touch trap —
 * HaloApp.kt).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiscoveredBridgeList(
    bridges: List<BridgeDiscovery.DiscoveredBridge>,
    onSelect: (BridgeDiscovery.DiscoveredBridge) -> Unit,
    onBack: () -> Unit,
) {
    // Top-anchor the list so the first AND second rows are both on screen at
    // once. ScalingLazyColumn's default autoCentering reserves ~half a screen of
    // padding above item 0 so it can be scrolled to center — that reservation is
    // exactly what pushed the bridge rows into the lower half (whether item 0 or
    // item 1 was the initial center). Dropping autoCentering (null, below) and
    // giving an explicit top inset instead lets the "select a bridge" caption sit
    // near the top with the first tappable row directly beneath it.
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
        ScalingLazyColumn(
            state = listState,
            autoCentering = null,
            verticalArrangement = Arrangement.spacedBy(5.dp),
            contentPadding = PaddingValues(
                start = Halo.Geo.SafeInset,
                end = Halo.Geo.SafeInset,
                top = Halo.Geo.ListTopInset,
                bottom = Halo.Geo.ListBottomInset,
            ),
            modifier = Modifier
                .fillMaxSize()
                .background(Halo.Palette.Background)
                // Back = a pull-down from the resting top bound; fires only if the
                // list was already at the top when the gesture began (HaloGestures).
                .nestedScroll(rememberAtTopBackConnection(listState, onBack))
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
                    color = Halo.Palette.TextSecondary,
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
            color = Halo.Palette.TextSecondary,
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
 * real finger could tap and type and end up with an empty field. This used
 * to launch the Wear RemoteInput activity instead, but the system surface it
 * lands on is whatever keyboard the OEM ships, and the Samsung qwerty offers
 * NO route to a digit (no ?123 toggle; long-press yields accents) — a live
 * pairing attempt dead-ended on it. All three fields are digits-and-dots, so
 * the fix is to keep every IME out of the loop: the row opens the in-app
 * [DigitPadPane], which composes the value from plain buttons. The tag stays
 * on the tappable row so the instrumented pairing leg can still find it (it
 * drives the value through a test seam, not the pad).
 */
@Composable
private fun PairField(
    label: String,
    value: String,
    tag: String,
    onEdit: () -> Unit,
    onChange: (String) -> Unit,
) {
    Text(label, fontSize = Halo.Type.Min, color = Halo.Palette.TextSecondary)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Halo.Palette.Surface, RoundedCornerShape(8.dp))
            .clickable {
                // The instrumented pairing leg predates the pad and fills the
                // value through this seam on tap (see PairFieldInput).
                // Production leaves the seam null and opens the digit pad —
                // the only path a finger takes.
                val seam = PairFieldInput.override
                if (seam != null) {
                    seam(label)?.let { onChange(it.trim()) }
                    return@clickable
                }
                onEdit()
            }
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .testTag(tag),
    ) {
        Text(
            text = value.ifEmpty { "tap to enter" },
            fontSize = Halo.Type.Body,
            color = if (value.isEmpty()) Halo.Palette.TextSecondary else Halo.Palette.TextPrimary,
        )
    }
    Spacer(Modifier.height(4.dp))
}

/**
 * Full-screen digit pad for one pairing field. Host, port and code are all
 * digits (host adds dots), so twelve on-screen buttons cover the entire input
 * alphabet with no IME anywhere in the loop. Commit is explicit (the tick);
 * swipe-back/system back cancels, leaving the field as it was. Key sizes are
 * squeezed so the 4-row grid + value line + tick fit a 1.2" round face
 * without scrolling — a keypad that scrolls under a typing finger is worse
 * than small keys.
 */
@Composable
private fun DigitPadPane(
    label: String,
    initial: String,
    allowDot: Boolean,
    maxLen: Int,
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var value by remember(label) { mutableStateOf(initial) }
    BackHandler { onCancel() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().testTag("digitPad"),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$label ", fontSize = Halo.Type.Min, color = Halo.Palette.TextSecondary)
            Text(
                text = value.ifEmpty { " " }, // figure space holds the line height
                fontSize = Halo.Type.Body,
                color = Halo.Palette.TextPrimary,
                maxLines = 1,
                modifier = Modifier.testTag("padValue"),
            )
        }
        Spacer(Modifier.height(4.dp))
        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf(if (allowDot) "." else "", "0", "⌫"),
        )
        for (row in rows) {
            Row {
                for (key in row) {
                    if (key.isEmpty()) {
                        Spacer(Modifier.size(width = 44.dp, height = 34.dp))
                        continue
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .padding(horizontal = 2.dp, vertical = 1.dp)
                            .size(width = 40.dp, height = 32.dp)
                            .background(Halo.Palette.Surface, RoundedCornerShape(8.dp))
                            .clickable {
                                value = when {
                                    key == "⌫" -> value.dropLast(1)
                                    value.length >= maxLen -> value
                                    else -> value + key
                                }
                            }
                            .testTag(if (key == "⌫") "padDel" else "padKey$key"),
                    ) {
                        Text(key, fontSize = Halo.Type.Body, color = Halo.Palette.TextPrimary)
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = 128.dp, height = 30.dp)
                .background(Halo.Palette.WaitingForYou, RoundedCornerShape(15.dp))
                .clickable { onDone(value.trim()) }
                .testTag("padDone"),
        ) {
            Text("✓", fontSize = Halo.Type.Body, color = Halo.Palette.ApproveText)
        }
    }
}

/**
 * Test seam for [PairField] (the resolver-seam idiom used by
 * BridgeSessionService/GlanceStateSource). When [override] is non-null,
 * tapping a pairing field routes to it — returning the string to fill, or
 * null to leave the field unchanged (a cancelled input) — instead of opening
 * the in-app [DigitPadPane], sparing the harness a screenful of key taps.
 * Production never sets it, so a real finger always gets the pad.
 */
internal object PairFieldInput {
    @Volatile
    internal var override: ((label: String) -> String?)? = null
}
