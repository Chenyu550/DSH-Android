package io.github.jackwener.dsh.feature.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.data.WorkDetails
import io.github.jackwener.dsh.core.designsystem.BtnSize
import io.github.jackwener.dsh.core.designsystem.BtnVariant
import io.github.jackwener.dsh.core.designsystem.Chevron
import io.github.jackwener.dsh.core.designsystem.DButton
import io.github.jackwener.dsh.core.designsystem.Dsh
import io.github.jackwener.dsh.core.designsystem.DshMarkdown
import io.github.jackwener.dsh.core.designsystem.DshType
import io.github.jackwener.dsh.core.designsystem.Fmt
import io.github.jackwener.dsh.core.designsystem.HDivider
import io.github.jackwener.dsh.core.designsystem.IconBtn
import io.github.jackwener.dsh.core.designsystem.Radius
import io.github.jackwener.dsh.core.designsystem.ShimmerText
import io.github.jackwener.dsh.core.designsystem.SwimmingWhale
import io.github.jackwener.dsh.core.designsystem.Tag
import io.github.jackwener.dsh.core.designsystem.Tone
import io.github.jackwener.dsh.core.designsystem.copyText
import io.github.jackwener.dsh.core.designsystem.elevated
import io.github.jackwener.dsh.core.designsystem.pressable
import io.github.jackwener.dsh.core.session.Block
import io.github.jackwener.dsh.core.session.Conversation
import io.github.jackwener.dsh.core.session.EndReason
import io.github.jackwener.dsh.core.session.SessionLive
import io.github.jackwener.dsh.core.session.SteerMessage
import io.github.jackwener.dsh.core.session.ToolState
import io.github.jackwener.dsh.core.session.Turn
import io.github.jackwener.dsh.core.provider.NetDiagnose
import io.github.jackwener.dsh.core.session.TurnError
import io.github.jackwener.dsh.core.session.TurnStatus
import io.github.jackwener.dsh.core.session.UserMsg
import io.github.jackwener.dsh.core.session.ForkKind
import io.github.jackwener.dsh.core.session.Lineage
import io.github.jackwener.dsh.core.designsystem.DMenu
import io.github.jackwener.dsh.core.designsystem.MenuDivider
import io.github.jackwener.dsh.core.designsystem.MenuItem
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The message stream (dsh ChatView): user bubbles, per-turn header, folded process rows, plain
 * markdown replies, error rows and the running whale.
 *
 * Follows the tail: while following, the list is kept at the bottom whenever anything below the
 * viewport grows — new events, and also items that lay out late (markdown parses off the main
 * thread, so a reopened conversation's replies grow after the first frame; a one-shot scroll on
 * open used to land mid-conversation, #76). Dragging the list stops following unless it comes to
 * rest at the bottom; reaching the bottom or "回到底部" follows again. Following is saved with the
 * list position, so returning to the conversation shows the latest, or where the user was reading.
 */
@Composable
fun ChatStream(
    conv: Conversation,
    live: SessionLive,
    details: WorkDetails,
    listState: LazyListState,
    onInspect: (String) -> Unit,
    onRetry: (TurnError) -> Unit,
    onOpenSettings: (section: String) -> Unit,
    modifier: Modifier = Modifier,
    onContinue: () -> Unit = {},
    compact: Boolean = false,
    /** 编辑 / 重试 / 从这里分叉 on messages (#2); null: none (the quick panel). */
    actions: MessageActions? = null,
    /** Where the conversation stands among its forks: a line above its messages. */
    lineage: Lineage = Lineage(),
    onOpenSession: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var follow by rememberSaveable { mutableStateOf(true) }
    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
    LaunchedEffect(atBottom) { if (atBottom) follow = true }
    // One stream shown for another conversation (the quick panel): its latest.
    var shown by rememberSaveable { mutableStateOf(live.sessionId) }
    LaunchedEffect(live.sessionId) { if (shown != live.sessionId) { shown = live.sessionId; follow = true } }
    // A drag stops following; where the scroll comes to rest (after any fling) decides.
    LaunchedEffect(listState) {
        var dragged = false
        launch { listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) { dragged = true; follow = false } } }
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling -> if (!scrolling && dragged) { dragged = false; follow = !listState.canScrollForward } }
    }
    // A tap in the list (opening a step group, a long message) grows it under the finger: keep the view
    // where the user looks until new content arrives.
    var hold by remember { mutableStateOf(false) }
    val tailKey = conv.turns.lastOrNull()?.let { it.blocks.size to it.status } to (live.running to live.steers.size)
    LaunchedEffect(tailKey) { hold = false }
    // Keep the tail in view while following, whatever made the content grow.
    LaunchedEffect(listState) {
        snapshotFlow { follow && !hold && listState.canScrollForward && !listState.isScrollInProgress }.collect { pin ->
            if (pin) listState.layoutInfo.totalItemsCount.takeIf { it > 0 }?.let { listState.scrollToItem(it - 1, Int.MAX_VALUE / 2) }
        }
    }
    val lastIndex = conv.turns.size + live.steers.size

    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize().pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); hold = true } },
            state = listState, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)) {
            if (!lineage.isEmpty) item(key = "lineage") { LineageRow(lineage, onOpenSession) }
            // Forking needs the conversation at rest (no turn running, no fork being made).
            val turnActions = actions?.takeIf { !live.running }
            items(conv.turns, key = { it.key }) { turn ->
                val isLast = turn === conv.turns.last()
                TurnView(turn, isLast, live, details, onInspect, onRetry, onOpenSettings, onContinue, compact,
                    actions?.let { a -> TurnActions(a, conv.turns, turn, enabled = turnActions != null) })
            }
            // Sent into the running turn, not logged yet: the logged user/message takes its place.
            items(live.steers, key = { "steer/${it.id}" }) { PendingSteer(it) }
            item(key = "tail") {
                Box(Modifier.fillMaxWidth().heightIn(min = 8.dp)) {
                    if (live.running) RunningIndicator(live, conv.turns.lastOrNull())
                }
            }
        }
        AnimatedVisibility(!follow && !atBottom, Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 12.dp), enter = fadeIn(), exit = fadeOut()) {
            val c = Dsh.colors
            Box(Modifier.size(34.dp).elevated(CircleShape, c.borderL2).clip(CircleShape).background(c.floating)
                .pressable({ follow = true; hold = false; scope.launch { listState.animateScrollToItem(lastIndex, Int.MAX_VALUE / 2) } }, CircleShape),
                contentAlignment = Alignment.Center) {
                Chevron(size = 16.dp, tint = c.labelSecondary)
                val label = stringResource(R.string.back_to_bottom)
                Box(Modifier.matchParentSize().semantics { contentDescription = label; role = androidx.compose.ui.semantics.Role.Button })
            }
        }
    }
}

@Composable
private fun TurnView(
    turn: Turn, isLast: Boolean, live: SessionLive, details: WorkDetails,
    onInspect: (String) -> Unit, onRetry: (TurnError) -> Unit, onOpenSettings: (String) -> Unit, onContinue: () -> Unit, compact: Boolean,
    actions: TurnActions?,
) {
    val running = turn.status == TurnStatus.Running
    val process = turn.process
    val defaultOpen = when (details) {
        WorkDetails.Compact -> false
        WorkDetails.Standard -> isLast
        WorkDetails.Detailed -> true
    }
    var open by rememberSaveable(turn.key) { mutableStateOf(defaultOpen) }
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        turn.user?.let { if (DshNotice.isNotice(it.text)) DshNoticeRow(it.text) else UserMessage(it, compact, actions?.takeIf { _ -> it.messageId != null }) }
        if (process.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            if (!running) TurnHeader(turn, open) { open = !open }
            if (running || open) {
                Spacer(Modifier.height(if (running) 0.dp else 12.dp))
                ProcessBlocks(turn.key, process, running, live.sessionId, onInspect)
            }
        }
        turn.reply?.let { reply ->
            Spacer(Modifier.height(12.dp))
            SelectionContainer { DshMarkdown(reply.text) }
        }
        val end = turn.endReason
        if (turn.status == TurnStatus.Stopped && turn.error == null && !running && end != EndReason.Budget) {
            Spacer(Modifier.height(8.dp)); Tag(stringResource(R.string.stopped), Tone.Neutral)
        }
        if (end != null && end.continuable) EndCard(turn, end, canContinue = isLast && !live.running, onContinue, onOpenSettings)
        turn.error?.let { ErrorRow(it, onRetry, onOpenSettings, retryable = isLast && !live.running) }
        if (turn.reply != null && !running) TailActions(turn, actions?.takeIf { it.enabled && turn.user?.messageId != null })
    }
}

/** Process rows: thinking/tool runs become step groups; interim assistant text renders as markdown. */
@Composable
private fun ProcessBlocks(turnKey: String, blocks: List<Block>, live: Boolean, sessionId: String, onInspect: (String) -> Unit) {
    val segments = remember(blocks) {
        val out = mutableListOf<List<Block>>()
        var run = mutableListOf<Block>()
        for (b in blocks) {
            if (b is Block.Reasoning || b is Block.Tool) run += b
            else { if (run.isNotEmpty()) { out += run; run = mutableListOf() }; out += listOf(b) }
        }
        if (run.isNotEmpty()) out += run
        out
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        segments.forEachIndexed { i, seg ->
            val segLive = live && i == segments.lastIndex
            when (val b = seg.first()) {
                is Block.Text -> Box(Modifier.padding(vertical = 8.dp)) { DshMarkdown(b.text) }
                is Block.Compaction -> CompactionRow(b)
                is Block.Notice -> NoticeRow(b)
                else -> StepGroup("$turnKey/$i", seg, segLive, sessionId, onInspect)
            }
        }
    }
}

/** Turn header (dsh TurnProcessNodeView): "已完成，用时 …" + rotating chevron, hairline underline. */
@Composable
private fun TurnHeader(turn: Turn, open: Boolean, onToggle: () -> Unit) {
    val c = Dsh.colors
    val context = LocalContext.current
    val text = when (turn.status) {
        TurnStatus.Completed, TurnStatus.MaxTokens -> turn.endTime?.let { stringResource(R.string.turn_done_in, Fmt.duration(context, it - (turn.user?.time ?: turn.startTime))) }
            ?: stringResource(R.string.turn_done)
        TurnStatus.Failed -> stringResource(R.string.turn_failed)
        TurnStatus.Stopped -> stringResource(R.string.turn_stopped)
        TurnStatus.Steered -> stringResource(R.string.turn_steered)
        TurnStatus.Running -> ""
    }
    Column(Modifier.fillMaxWidth().pressable(onToggle, Radius.sm)) {
        Row(Modifier.fillMaxWidth().height(33.dp).padding(bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
            Text(text, Modifier.weight(1f), style = DshType.s14, color = c.labelTertiary)
            Chevron(open = open)
        }
        HDivider()
    }
}

/**
 * A human message. With [actions] (it reached the engine), a long press opens 编辑 / 重试 / 从这里分叉 / 复制
 * (#2; greyed while the conversation runs) and the row below it has 编辑 next to 复制.
 */
@Composable
private fun UserMessage(u: UserMsg, compact: Boolean, actions: TurnActions?) {
    val c = Dsh.colors
    val context = LocalContext.current
    val maxW = (LocalConfiguration.current.screenWidthDp * 0.82f).dp
    var menu by remember { mutableStateOf(false) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val longPress = actions?.let { { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); menu = true } }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Box {
            Column(horizontalAlignment = Alignment.End) {
                if (u.images > 0 || u.files > 0) {
                    Row(Modifier.padding(bottom = 8.dp).then(longPress?.let { Modifier.longPress(it) } ?: Modifier), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (u.images > 0) Tag(stringResource(R.string.images_n, u.images))
                        if (u.files > 0) Tag(stringResource(R.string.files_n, u.files))
                    }
                }
                if (u.text.isNotBlank()) UserBubble(u.text, Modifier.widthIn(max = maxW), onLongPress = longPress)
            }
            if (actions != null) DMenu(menu, { menu = false }) {
                MenuItem(stringResource(R.string.msg_edit), { menu = false; actions.edit(u) }, R.drawable.dsw_edit_outline,
                    stringResource(R.string.msg_edit_desc), enabled = actions.enabled)
                MenuItem(stringResource(R.string.msg_retry), { menu = false; actions.retry(u) }, R.drawable.dsw_refresh_outline,
                    stringResource(R.string.msg_retry_desc), enabled = actions.enabled)
                MenuItem(stringResource(R.string.msg_fork), { menu = false; actions.fork() }, R.drawable.dsw_branch_outline,
                    stringResource(R.string.msg_fork_desc), enabled = actions.enabled)
                MenuDivider()
                MenuItem(stringResource(R.string.copy_message), { menu = false; copyText(context, u.text) }, R.drawable.dsw_copy_outline)
            }
        }
        if (!compact) Row(Modifier.padding(top = 6.dp).height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Fmt.stamp(context, u.time), style = DshType.xs13, color = c.labelTertiary)
            IconBtn(R.drawable.dsw_copy_outline, { copyText(context, u.text) }, iconSize = 15.dp, tint = c.labelTertiary, contentDescription = stringResource(R.string.copy_message))
            if (actions?.enabled == true) IconBtn(R.drawable.dsw_edit_outline, { actions.edit(u) }, iconSize = 15.dp, tint = c.labelTertiary, contentDescription = stringResource(R.string.msg_edit))
        }
    }
}

/** A long press (no ripple, no tap action): the message menu. */
private fun Modifier.longPress(onLongPress: () -> Unit): Modifier =
    pointerInput(onLongPress) { detectTapGestures(onLongPress = { onLongPress() }) }

/**
 * The user's words in a bubble, as markdown (#85) with every line break kept: what was typed or pasted
 * keeps its shape. A long one (#76) is folded to [USER_FOLD_LINES] lines with 展开全部 / 收起.
 */
@Composable
private fun UserBubble(text: String, modifier: Modifier = Modifier, onLongPress: (() -> Unit)? = null) {
    val c = Dsh.colors
    val density = LocalDensity.current
    val foldPx = with(density) { (DshType.body.lineHeight * USER_FOLD_LINES).roundToPx() + 12.dp.roundToPx() }
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var long by remember(text) { mutableStateOf(false) }
    val folded = long && !expanded
    // With a message menu the long press opens it (复制 is there); text selection is for bubbles without one.
    Column(modifier.clip(Radius.xl).background(c.bubble).then(onLongPress?.let { Modifier.longPress(it) } ?: Modifier).padding(horizontal = 16.dp, vertical = 6.dp)) {
        Box(Modifier.clipToBounds()
            .drawWithContent {
                drawContent()
                if (folded) drawRect(Brush.verticalGradient(listOf(Color.Transparent, c.bubble), startY = size.height - 28.dp.toPx(), endY = size.height))
            }
            .layout { m, cs ->
                val p = m.measure(cs.copy(maxHeight = Constraints.Infinity))
                // Fold only what is clearly longer: hiding one or two lines behind a button helps no one.
                val isLong = p.height > foldPx * 3 / 2
                if (isLong != long) long = isLong
                layout(p.width, if (isLong && !expanded) foldPx else p.height) { p.place(0, 0) }
            }) {
            if (onLongPress != null) DshMarkdown(text, breaks = true) else SelectionContainer { DshMarkdown(text, breaks = true) }
        }
        if (long) Text(stringResource(if (expanded) R.string.user_msg_collapse else R.string.user_msg_expand),
            Modifier.padding(top = 2.dp, bottom = 4.dp).clip(Radius.sm).pressable({ expanded = !expanded }, Radius.sm).padding(vertical = 2.dp),
            style = DshType.xs13m, color = c.labelSecondary)
    }
}

/** Lines a long user message shows while folded. */
private const val USER_FOLD_LINES = 5

/** A message steered into the running turn that dsh has not logged yet: the user bubble, dimmed, with its state. */
@Composable
private fun PendingSteer(m: SteerMessage) {
    val maxW = (LocalConfiguration.current.screenWidthDp * 0.82f).dp
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).alpha(0.6f), horizontalAlignment = Alignment.End) {
        if (m.images > 0) Tag(stringResource(R.string.images_n, m.images), modifier = Modifier.padding(bottom = 8.dp))
        if (m.text.isNotBlank()) UserBubble(m.text, Modifier.widthIn(max = maxW))
        Spacer(Modifier.height(6.dp))
        Tag(stringResource(R.string.steer_pending), Tone.Neutral)
    }
}

@Composable
private fun TailActions(turn: Turn, actions: TurnActions?) {
    val c = Dsh.colors
    val context = LocalContext.current
    Row(Modifier.padding(top = 10.dp).offset(x = (-6).dp), verticalAlignment = Alignment.CenterVertically) {
        IconBtn(R.drawable.dsw_copy_outline, { copyText(context, turn.reply?.text ?: "") }, iconSize = 17.dp, tint = c.labelTertiary, contentDescription = stringResource(R.string.copy_reply))
        // #2: a new reply to the same message, or a new conversation from here (both keep this one).
        turn.user?.let { u -> actions?.let { a ->
            IconBtn(R.drawable.dsw_refresh_outline, { a.retry(u) }, iconSize = 17.dp, tint = c.labelTertiary, contentDescription = stringResource(R.string.msg_retry))
            IconBtn(R.drawable.dsw_branch_outline, { a.fork() }, iconSize = 17.dp, tint = c.labelTertiary, contentDescription = stringResource(R.string.msg_fork))
        } }
        if (!turn.usage.isEmpty) {
            Spacer(Modifier.width(4.dp))
            val hit = turn.usage.cacheHitRate?.let { " · " + stringResource(R.string.usage_cache_hit_short, Fmt.percent(it)) }.orEmpty()
            Text("↑${Fmt.tokens(turn.usage.input)} ↓${Fmt.tokens(turn.usage.output)}$hit", Modifier.clip(Radius.sm).background(c.selector).padding(horizontal = 8.dp, vertical = 1.dp),
                style = DshType.xxs12, color = c.labelTertiary)
        }
        Spacer(Modifier.width(8.dp))
        turn.endTime?.let { Text(Fmt.stamp(context, it), style = DshType.xxs12, color = c.labelCaption) }
    }
}

/** dsh turn error: red dot | title + message + code, with a retry affordance. */
@Composable
private fun ErrorRow(e: TurnError, onRetry: (TurnError) -> Unit, onOpenSettings: (String) -> Unit, retryable: Boolean) {
    val c = Dsh.colors
    val account = io.github.jackwener.dsh.feature.account.LocalAccount.current
    val openPlatform = io.github.jackwener.dsh.feature.account.rememberPlatformOpener()
    // No credential at all, or the account route refused the request (dsh modelSignInRequired).
    val needsSignIn = e.kind == "no_credentials" || e.kind == "no_key" || e.code == "ACCOUNT_SIGN_IN_REQUIRED" || e.code == "ACCOUNT_TOKEN_INVALID"
    val accountQuota = e.code == "ACCOUNT_QUOTA"
    // A third-party endpoint that could not be reached: what it means + what to check (js patch 14).
    val net = remember(e) { NetDiagnose.ofEngineError(e.code, e.message) }
    // The provider answered in a shape the adapter cannot read (js patch 16): not the network.
    val format = remember(e) { NetDiagnose.formatErrorOf(e.code, e.message) }
    // The account has not enabled the model (js patch 18): not the key, not the network.
    val unavailable = remember(e) { NetDiagnose.modelUnavailableOf(e.code, e.message) }
    val title = when {
        needsSignIn -> stringResource(R.string.acct_model_sign_in_required)
        net != null -> stringResource(R.string.net_title)
        unavailable != null -> stringResource(R.string.err_model_unavailable_title)
        accountQuota -> stringResource(R.string.acct_quota_title)
        e.kind == "engine" -> stringResource(R.string.err_engine_title)
        e.kind == "engine_lost" -> stringResource(R.string.err_engine_lost_title)
        e.kind == io.github.jackwener.dsh.core.remote.RemoteErrors.KIND -> stringResource(R.string.remote_error_title)
        else -> stringResource(R.string.turn_error_title)
    }
    val message = when {
        e.kind == "no_credentials" || e.kind == "no_key" -> stringResource(R.string.err_no_key)
        needsSignIn -> ""
        accountQuota -> stringResource(R.string.acct_quota_desc)
        net != null -> stringResource(net.cause.message) + (net.cause.hint?.let { " " + stringResource(it) } ?: "")
        format != null -> stringResource(R.string.err_provider_format, format)
        unavailable != null -> stringResource(R.string.err_model_unavailable, unavailable.model ?: "?", unavailable.serverText)
        e.code?.contains("auth", true) == true || e.message.contains("401") -> stringResource(R.string.err_invalid_key)
        e.message.contains("402") || e.message.contains("insufficient", true) -> stringResource(R.string.err_quota)
        else -> e.message
    }
    Row(Modifier.padding(top = 12.dp)) {
        Box(Modifier.padding(top = 5.dp).size(10.dp).clip(CircleShape).background(c.error))
        Spacer(Modifier.width(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = DshType.xs13.copy(fontWeight = FontWeight(600)), color = c.error)
            if (message.isNotBlank()) SelectionContainer { Text(message.take(600), style = DshType.xs13, color = c.labelSecondary) }
            if (net != null) SelectionContainer { Text(net.detail.take(300), style = DshType.mono11, color = c.labelTertiary) }
            else if (e.code != null && e.kind != "engine" && !needsSignIn && !accountQuota) Text(e.code.take(300), style = DshType.mono11, color = c.labelTertiary)
            if (e.kind == "engine" && e.code != null) Text(e.code.take(600), style = DshType.mono11, color = c.labelTertiary)
            if (retryable) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    needsSignIn -> {
                        DButton(stringResource(R.string.acct_sign_in), { if (account != null) account.startSignIn() else onOpenSettings("Account") }, size = BtnSize.Sm)
                        DButton(stringResource(R.string.acct_add_api_key), { onOpenSettings("Model") }, size = BtnSize.Sm, variant = BtnVariant.Outline)
                        if (e.kind != "no_credentials" && e.kind != "no_key" || account?.signedIn == true)
                            DButton(stringResource(R.string.retry), { onRetry(e) }, size = BtnSize.Sm, variant = BtnVariant.Outline, icon = R.drawable.dsw_refresh_outline)
                    }
                    accountQuota -> {
                        DButton(stringResource(R.string.acct_quota_top_up), { openPlatform(account?.state?.value?.topUpUrl ?: "https://platform.deepseek.com/top_up") }, size = BtnSize.Sm)
                        DButton(stringResource(R.string.retry), { onRetry(e) }, size = BtnSize.Sm, variant = BtnVariant.Outline, icon = R.drawable.dsw_refresh_outline)
                    }
                    unavailable != null -> {
                        DButton(stringResource(R.string.err_open_model_settings), { onOpenSettings("Model") }, size = BtnSize.Sm)
                        DButton(stringResource(R.string.retry), { onRetry(e) }, size = BtnSize.Sm, variant = BtnVariant.Outline, icon = R.drawable.dsw_refresh_outline)
                    }
                    // Remote mode (#51): the computer could not be reached, refused us, or has no usable dsh.
                    e.kind == io.github.jackwener.dsh.core.remote.RemoteErrors.KIND -> {
                        DButton(stringResource(R.string.retry), { onRetry(e) }, size = BtnSize.Sm, icon = R.drawable.dsw_refresh_outline)
                        DButton(stringResource(R.string.remote_open_settings), { onOpenSettings("Remote") }, size = BtnSize.Sm, variant = BtnVariant.Outline)
                    }
                    e.kind == "engine" || e.kind == "engine_lost" -> {
                        DButton(stringResource(R.string.restart_engine_retry), { onRetry(e) }, size = BtnSize.Sm, icon = R.drawable.dsw_refresh_outline)
                        // Settings → 关于: engine status, 自检 (crash breaker, last exit), engine log, exit record.
                        DButton(stringResource(R.string.err_open_doctor), { onOpenSettings("About") }, size = BtnSize.Sm, variant = BtnVariant.Outline)
                    }
                    else -> DButton(stringResource(R.string.retry), { onRetry(e) }, size = BtnSize.Sm, variant = BtnVariant.Outline, icon = R.drawable.dsw_refresh_outline)
                }
            }
        }
    }
}

/**
 * A turn that ended with work left (#37): why it stopped, the todo progress, what is next, and 继续
 * (only on the last turn while nothing runs). Never looks like a normal reply.
 */
@Composable
private fun EndCard(turn: Turn, end: EndReason, canContinue: Boolean, onContinue: () -> Unit, onOpenSettings: (String) -> Unit) {
    val c = Dsh.colors
    val todos = turn.todoCounts
    val title = when (end) {
        EndReason.PendingWork -> stringResource(R.string.end_pending_title)
        EndReason.ContinueLimit -> stringResource(R.string.end_limit_title, turn.outcome?.nudges ?: 0)
        EndReason.Budget -> stringResource(R.string.end_budget_title)
        else -> stringResource(R.string.output_cap)
    }
    val hint = when (end) {
        EndReason.Budget -> stringResource(R.string.end_budget_hint)
        EndReason.MaxTokens -> stringResource(R.string.output_cap_hint)
        else -> stringResource(R.string.end_continue_hint)
    }
    val next = turn.todos?.firstOrNull { it.status == "in_progress" } ?: turn.todos?.firstOrNull { it.status == "pending" }
    Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(Radius.md).background(c.selector).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = DshType.xs13.copy(fontWeight = FontWeight(600)), color = c.amber)
        if (todos != null && todos.total > 0) Text(stringResource(R.string.end_progress, todos.completed, todos.total), style = DshType.xs13, color = c.labelSecondary)
        next?.let { Text(stringResource(R.string.end_next, it.content), style = DshType.xs13, color = c.labelSecondary) }
        Text(hint, style = DshType.xs13, color = c.labelTertiary)
        if (canContinue) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DButton(stringResource(R.string.continue_task), onContinue, size = BtnSize.Sm)
            if (end == EndReason.Budget) DButton(stringResource(R.string.end_budget_settings), { onOpenSettings("Agent") }, size = BtnSize.Sm, variant = BtnVariant.Outline)
        }
    }
}

/**
 * What a running turn is doing, for the status line (#76). dsh logs a step only when it ends, so between
 * tool calls the model is reading the results and writing its next step: that is said, with the count of
 * steps done, instead of the bare 深度求索中 a long multi-tool run used to show throughout.
 */
sealed interface RunPhase {
    /** No tool yet: the model works on the request. */
    data object Thinking : RunPhase
    /** A tool call runs. */
    data class Tool(val tool: Block.Tool) : RunPhase
    /** Between steps: [done] tool calls have finished; the model decides the next one. */
    data class NextStep(val done: Int) : RunPhase

    companion object {
        fun of(turn: Turn?): RunPhase {
            val blocks = turn?.takeIf { it.status == TurnStatus.Running }?.blocks ?: return Thinking
            (blocks.lastOrNull { it is Block.Tool && it.state == ToolState.Running } as Block.Tool?)?.let { return Tool(it) }
            val done = blocks.count { it is Block.Tool }
            return if (done == 0) Thinking else NextStep(done)
        }
    }
}

/**
 * dsh RunningStatus: whale + what the run does now ([RunPhase]: 深度求索中 / 正在执行命令：npm install / 思考下一步)
 * in deep-diving blue with shimmer, the elapsed time after it, ticking each second.
 */
@Composable
fun RunningIndicator(live: SessionLive, lastTurn: Turn?) {
    val c = Dsh.colors
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val start = lastTurn?.takeIf { it.status == TurnStatus.Running }?.let { it.user?.time ?: it.startTime } ?: live.runStartedAt ?: now
    var working = false
    val text = when {
        live.startingEngine -> stringResource(R.string.starting_engine)
        live.waitingForUnlock -> stringResource(R.string.waiting_unlock)
        live.waitingForNetwork -> stringResource(R.string.waiting_network)
        live.takeover != null -> stringResource(R.string.takeover_title)
        live.approval != null || live.permission != null -> stringResource(R.string.waiting_approval_inline)
        live.question != null -> stringResource(R.string.waiting_answer_inline)
        else -> {
            working = true
            when (val phase = RunPhase.of(lastTurn)) {
                RunPhase.Thinking -> stringResource(R.string.run_thinking)
                is RunPhase.NextStep -> stringResource(R.string.run_next_step, phase.done)
                is RunPhase.Tool -> {
                    val activity = stringResource(ToolPresentation.info(phase.tool.name).activity.live)
                    ToolPresentation.summary(context, phase.tool)?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { stringResource(R.string.run_tool, activity, it.take(60)) } ?: activity
                }
            }
        }
    }
    Column(Modifier.padding(top = 4.dp)) {
        if (lastTurn?.blocks?.isNotEmpty() == true) HDivider(Modifier.padding(bottom = 4.dp))
        Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SwimmingWhale(14.dp, c.deepDiving)
            ShimmerText(text, DshType.xxs12.copy(lineHeight = DshType.xxs12.lineHeight), c.deepDiving, true, Modifier.weight(1f, fill = false), highlight = c.deepDivingShimmer)
            if (working) Text(stringResource(R.string.run_elapsed, Fmt.duration(context, now - start)), style = DshType.xxs12, color = c.labelTertiary, maxLines = 1)
        }
    }
}

/**
 * Prompts DSH itself sends as the user (e.g. the continuation after an engine crash) start with
 * this marker; they render as a system notice, not as the user's own bubble. SessionStore owns the
 * texts (CONTINUE_AFTER_CRASH); keep the prefix in sync with it.
 */
object DshNotice {
    const val PREFIX = "[DSH]"
    fun isNotice(text: String) = text.startsWith(PREFIX)
    fun isCrashContinuation(text: String) = isNotice(text) && "stopped unexpectedly" in text
}

@Composable
private fun DshNoticeRow(text: String) {
    val c = Dsh.colors
    val shown = if (DshNotice.isCrashContinuation(text)) stringResource(R.string.dsh_notice_crash) else text.removePrefix(DshNotice.PREFIX).trim()
    val label = stringResource(R.string.dsh_notice)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) { contentDescription = "$label: $shown" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(c.warn))
        Spacer(Modifier.width(8.dp))
        Text(shown, Modifier.weight(1f, fill = false), style = DshType.xs13, color = c.labelSecondary)
    }
}

/**
 * 编辑 / 重试 / 从这里分叉 (#2), from the screen that owns the composer. Each makes a new conversation from
 * this one (dsh's fork); [onFork] gets the turns shown and the turn to keep to its end.
 */
class MessageActions(
    val onEdit: (UserMsg) -> Unit,
    val onRetry: (UserMsg) -> Unit,
    val onFork: (List<Turn>, Turn) -> Unit,
)

/** [MessageActions] bound to one turn; [enabled] while nothing runs. */
class TurnActions(private val a: MessageActions, private val turns: List<Turn>, private val turn: Turn, val enabled: Boolean) {
    fun edit(u: UserMsg) { if (enabled) a.onEdit(u) }
    fun retry(u: UserMsg) { if (enabled) a.onRetry(u) }
    fun fork() { if (enabled) a.onFork(turns, turn) }
}

/** Above the messages of a forked or replaced conversation (#2): where it came from, or that a newer version exists. */
@Composable
private fun LineageRow(l: Lineage, onOpen: (String) -> Unit) {
    val c = Dsh.colors
    val newer = l.newer
    val source = l.source
    val (text, link, target) = when {
        newer != null -> Triple(stringResource(R.string.lineage_newer), stringResource(R.string.lineage_open_newer), newer.id)
        source != null && l.kind != null -> Triple(when (l.kind) {
            ForkKind.Edit -> stringResource(R.string.lineage_edit)
            ForkKind.Retry -> stringResource(R.string.lineage_retry)
            ForkKind.Fork -> stringResource(R.string.lineage_fork, source.title.ifBlank { stringResource(R.string.untitled) })
        }, stringResource(R.string.lineage_open_source), source.id)
        else -> return
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        io.github.jackwener.dsh.core.designsystem.DIcon(R.drawable.dsw_branch_outline, size = 13.dp, tint = c.labelTertiary)
        Spacer(Modifier.width(6.dp))
        Text(text, Modifier.weight(1f, fill = false), style = DshType.xxs12, color = c.labelTertiary, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        Text(link, Modifier.clip(Radius.sm).pressable({ onOpen(target) }, Radius.sm).padding(horizontal = 4.dp, vertical = 2.dp),
            style = DshType.xxs12m, color = c.labelSecondary, maxLines = 1)
    }
}
