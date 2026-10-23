package net.extrawdw.apps.notisync.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.animation.core.spring
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDragHandle
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.rememberPaneExpansionState
import androidx.compose.material3.adaptive.layout.PaneExpansionAnchor
import androidx.compose.material3.adaptive.navigation3.SupportingPaneSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberSupportingPaneSceneStrategy
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.OverlayScene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.ui.NavDisplay
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.navigation.AppNavigationTransitions
import net.extrawdw.apps.notisync.ui.icons.material.filled.arrow_back as ArrowBackIcon

/** Remains true within pane content, including its title/header. */
internal val LocalIsDetailPane = compositionLocalOf { false }
/** Supporting-pane geometry, excluding shared horizontal system insets. */
internal val LocalDetailPaneGeometry = compositionLocalOf<DetailPaneGeometry?> { null }
private val LocalInlineDetailSheet = compositionLocalOf { false }
private val LocalDetailPaneTitle = compositionLocalOf { "" }
/** Animated pane dismissal for details that provide their own header. */
internal val LocalDetailPaneBack = compositionLocalOf<(() -> Unit)?> { null }

/**
 * The same supporting-pane scene and motion as tablet pairing, with compact modal sheets.
 * Both presentations use the same decorated entries, retaining list/detail saveable state when
 * the window changes size. Item ids key the detail transition; record updates keep that identity.
 */
@Composable
internal fun <T> AdaptiveDetailLayout(
    selectedKey: String?,
    detailState: T,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    paneTitle: String = "",
    directive: PaneScaffoldDirective = calculatePaneScaffoldDirective(currentWindowAdaptiveInfoV2()),
    detail: @Composable (T) -> Unit,
    list: @Composable () -> Unit,
) {
    // Own horizontal safety once for the whole split, including the divider's end anchors.
    // Otherwise each pane repeats a landscape cutout inset at its interior edge as well.
    val paneInsets = if (directive.maxHorizontalPartitions > 1) {
        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    } else WindowInsets(0)
    BoxWithConstraints(modifier.fillMaxSize().windowInsetsPadding(paneInsets)) {
        // A parent pane (Devices/Pairing) can be narrower than the activity window.
        val wide = directive.maxHorizontalPartitions > 1 &&
            maxWidth >= directive.defaultPanePreferredWidth * 2 + directive.horizontalPartitionSpacerSize
        // Nav3 retains decorated entry content by key. Read the current presentation/content from
        // State inside that retained lambda, including when only the scene strategy changes.
        val currentWide = rememberUpdatedState(wide)
        val density = LocalDensity.current
        val geometry = remember(constraints.maxWidth, density) {
            DetailPaneGeometry(constraints.maxWidth, with(density) { DetailPaneSpacer.roundToPx() })
        }
        val currentGeometry = rememberUpdatedState(geometry)
        val currentDetail = rememberUpdatedState(detail)
        val currentList = rememberUpdatedState(list)
        // Keep the outgoing record and title while its exit animation runs. The renderer
        // receives that snapshot, rather than reading the newly selected record for both panes.
        val destination = remember { mutableStateOf<DetailDestination<T>?>(null) }
        if (selectedKey != null) destination.value = DetailDestination(selectedKey, paneTitle, detailState)
        val currentDismiss = rememberUpdatedState(onDismiss)
        val paneState = remember { SupportingPaneMotionState() }
        val anchors = remember {
            listOf(PaneExpansionAnchor.Proportion(0f), PaneExpansionAnchor.Proportion(0.5f), PaneExpansionAnchor.Proportion(1f))
        }
        val paneExpansion = rememberPaneExpansionState(
            anchors = anchors,
            anchoringAnimationSpec = spring(dampingRatio = 1f, stiffness = PaneAnchoringStiffness),
        )
        val paneOpen = wide && selectedKey != null
        val currentPaneOpen = rememberUpdatedState(paneOpen)
        val windowWidthPx = rememberUpdatedState(constraints.maxWidth)
        val interactionSource = remember { MutableInteractionSource() }
        val dragging = interactionSource.collectIsDraggedAsState()
        val contentInteractionSource = remember { MutableInteractionSource() }
        val contentDragging = contentInteractionSource.collectIsDraggedAsState()
        val contentDrag = rememberPaneContentDrag(paneExpansion)
        val coveringEnabled = {
            currentPaneOpen.value && !paneState.opening && !paneState.closing
        }
        val anchoredWidth: (Boolean) -> Int? = { main ->
            (paneExpansion.currentAnchor as? PaneExpansionAnchor.Proportion)?.let {
                currentGeometry.value.paneWidthAt(it.proportion, main)
            }
        }
        val splitDetailWidth = with(density) { geometry.splitDetailWidth.toDp() }
        var paneWidth by remember(maxWidth) { mutableStateOf(splitDetailWidth) }
        LaunchedEffect(paneOpen) {
            if (paneOpen) {
                paneWidth = splitDetailWidth
                paneState.open()
            } else paneState.reset()
        }
        val currentPaneWidth = rememberUpdatedState(paneWidth)
        val prepareClose = rememberUpdatedState<(androidx.compose.ui.unit.Dp) -> Unit>({ width ->
            paneWidth = width.coerceAtMost(maxWidth)
        })
        val mainWidthPx = with(density) {
            geometry.firstPaneWidthDuringMotion(paneWidth.roundToPx(), paneState.progress.value)
        }
        // Update only while our open/close progress changes. Rewriting this in SideEffect would
        // overwrite the native handle's drag position on every recomposition.
        LaunchedEffect(mainWidthPx) { paneExpansion.setFirstPaneWidth(mainWidthPx) }
        LaunchedEffect(paneExpansion.currentAnchor, paneOpen, contentDrag.isSettling) {
            if (paneOpen && !contentDrag.isSettling && paneExpansion.currentAnchor == anchors.last()) {
                paneExpansion.animateTo(anchors.last())
                currentDismiss.value()
            }
        }
        // Handle the single main pane with the SAME scene as the two-pane layout. Switching to
        // SinglePaneScene on close removes the lookahead parent while Material ListItems measure.
        val materialStrategy = rememberSupportingPaneSceneStrategy<String>(
            shouldHandleSinglePaneLayout = true,
            directive = PaneScaffoldDirective(
                maxHorizontalPartitions = directive.maxHorizontalPartitions,
                horizontalPartitionSpacerSize = with(density) { geometry.spacer.toDp() },
                maxVerticalPartitions = directive.maxVerticalPartitions,
                verticalPartitionSpacerSize = 0.dp,
                defaultPanePreferredWidth = directive.defaultPanePreferredWidth,
                defaultPanePreferredHeight = directive.defaultPanePreferredHeight,
                excludedBounds = directive.excludedBounds,
                // Auto-focus selects the first focusable descendant, which is Apps search.
                shouldAutoFocusCurrentDestination = false,
            ),
            paneExpansionState = paneExpansion,
            paneExpansionDragHandle = { state ->
                VerticalDragHandle(
                    modifier = Modifier.testTag("detail-pane-divider")
                        .onGloballyPositioned {
                            contentDrag.dividerCenter = it.positionInRoot().x + it.size.width / 2f
                        }
                        .paneExpansionDraggable(
                            state, LocalMinimumInteractiveComponentSize.current, interactionSource,
                        ),
                    interactionSource = interactionSource,
                )
            },
        )
        val paneMotion = remember {
            SupportingPaneSceneStrategy.paneAnimation(
                enterTransition = EnterTransition.None,
                exitTransition = ExitTransition.None,
                boundsAnimationSpec = snap(),
            )
        }
        val sheetStrategy = remember {
            SceneStrategy<String> { entries ->
                if (entries.size > 1) SheetDetailScene(entries.last(), entries.dropLast(1)) else null
            }
        }
        // Claim horizontal content swipes before the enclosing feature drawer. Child horizontal
        // scrollers and vertical lists retain their normal gesture priority and touch slop.
        val paneSwipe = Modifier.paneContentDraggable(
            drag = contentDrag,
            state = paneExpansion,
            anchors = anchors,
            geometry = geometry,
            enabled = paneOpen && !paneState.opening && !paneState.closing,
            interactionSource = contentInteractionSource,
        )
        val strategies = if (wide) listOf(materialStrategy, sheetStrategy) else listOf(sheetStrategy, materialStrategy)
        // Keep NavDisplay's native transitions: its scene-entry exclusion cleanup runs when
        // a transition completes. Zero-duration overrides can leave a returning list excluded.
        NavDisplay(
            modifier = paneSwipe,
            backStack = listOf("list") + listOfNotNull(selectedKey?.let { "detail" }),
            sceneStrategies = strategies,
            onBack = onDismiss,
            entryProvider = { key ->
                if (key == "list") {
                    NavEntry(key, metadata = SupportingPaneSceneStrategy.mainPane() + paneMotion) {
                        CoveringPaneContent(
                            windowWidthPx.value, coveringEnabled,
                            { dragging.value || contentDragging.value || contentDrag.isSettling }, { anchoredWidth(true) },
                            restoreSplitWidth = true,
                        ) { currentList.value() }
                    }
                } else {
                    NavEntry(key, metadata = SupportingPaneSceneStrategy.supportingPane() + paneMotion) {
                        CompositionLocalProvider(
                            LocalInlineDetailSheet provides currentWide.value,
                            LocalIsDetailPane provides currentWide.value,
                            LocalDetailPaneGeometry provides currentGeometry.value,
                        ) {
                            CoveringPaneContent(
                                windowWidthPx.value, coveringEnabled,
                                { dragging.value || contentDragging.value || contentDrag.isSettling }, { anchoredWidth(false) },
                                minimumContentWidth = currentGeometry.value.splitDetailWidth,
                            ) {
                                SlidingSupportingPane(
                                    paneState, currentPaneWidth.value, { currentDismiss.value() },
                                    enabled = currentWide.value,
                                    resizeToPane = !paneState.opening && !paneState.closing,
                                    onCloseStart = { prepareClose.value(it) },
                                ) { close ->
                                    CompositionLocalProvider(
                                        LocalDetailPaneBack provides close.takeIf { currentWide.value },
                                    ) {
                                        destination.value?.let { target ->
                                            AnimatedContent(
                                                targetState = target,
                                                modifier = Modifier.fillMaxSize(),
                                                contentKey = { it.key },
                                                transitionSpec = {
                                                    if (currentWide.value) {
                                                        (AppNavigationTransitions.enter togetherWith AppNavigationTransitions.exit)
                                                            .using(null)
                                                    } else {
                                                        (EnterTransition.None togetherWith ExitTransition.None).using(null)
                                                    }
                                                },
                                                label = "Detail selection",
                                            ) { shown ->
                                                CompositionLocalProvider(LocalDetailPaneTitle provides shown.title) {
                                                    currentDetail.value(shown.state)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
        )
    }
}

private data class DetailDestination<T>(val key: String, val title: String, val state: T)

/** The modal owns its window and Back gesture; its source list stays composed underneath it. */
private data class SheetDetailScene(
    val entry: NavEntry<String>,
    override val overlaidEntries: List<NavEntry<String>>,
) : OverlayScene<String> {
    override val key: Any = entry.contentKey
    override val entries = listOf(entry)
    override val previousEntries = overlaidEntries
    override val content: @Composable () -> Unit = { entry.Content() }
}

@Suppress("DEPRECATION")
@Composable
internal fun AdaptiveDetailSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalInlineDetailSheet.current) {
        InlineDetailSurface(onDismissRequest, content = content)
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            sheetState = sheetState,
            contentWindowInsets = contentWindowInsets,
            content = content,
        )
    }
}

@Composable
internal fun inlineHistoryDetailOrNull(
    onDismiss: () -> Unit,
    showPaneHeader: Boolean,
    content: @Composable ColumnScope.() -> Unit,
): Boolean {
    if (!LocalInlineDetailSheet.current) return false
    InlineDetailSurface(onDismiss, showPaneHeader, content)
    return true
}

@Composable
private fun InlineDetailSurface(
    onDismiss: () -> Unit,
    showPaneHeader: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val close = LocalDetailPaneBack.current ?: onDismiss
    val title = LocalDetailPaneTitle.current
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        topBar = {
            if (showPaneHeader) TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = close, modifier = Modifier.testTag("detail-pane-back")) {
                        Icon(ArrowBackIcon, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        TabContent {
            Column(Modifier.fillMaxSize().padding(padding.topAndSides()).consumeWindowInsets(padding)) {
                // Nested confirmations still open in their own modal window.
                CompositionLocalProvider(LocalInlineDetailSheet provides false) { content() }
            }
        }
    }
}
