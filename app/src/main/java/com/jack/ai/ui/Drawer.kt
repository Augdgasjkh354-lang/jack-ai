package com.jack.ai.ui

import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.luminance

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jack.ai.UiState
import com.jack.ai.data.Conversation

@Composable
fun HistoryDrawer(
    state: UiState,
    onNew: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onPin: (String) -> Unit,
    onMemory: (Conversation) -> Unit,
    onOpenProject: (String) -> Unit,
    onNewProject: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenRoutines: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenTables: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenDownloads: () -> Unit,
    onSettings: () -> Unit,
    onOpenFiles: () -> Unit = {},
    onOpenGames: () -> Unit = {},
    /** 点了「我的页面」里的一项，参数是它的相对路径（见 PinnedPages）。 */
    onOpenPage: (String) -> Unit = {},
    onUndoDelete: () -> Boolean = { false },
    /** 点了「聊天」：就是关上侧边栏。 */
    onCloseDrawer: () -> Unit = {},
    /** 在侧边栏里拖动改了底栏。 */
    onSaveTabs: (List<String>) -> Unit = {},
    /** 拖动改了「其他」那组的顺序。 */
    onSaveOrder: (List<String>) -> Unit = {},
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    // 删对话不再先弹确认框：直接删，抽屉底下出一条「撤销」
    val undoBar = remember { androidx.compose.material3.SnackbarHostState() }
    val undoScope = androidx.compose.runtime.rememberCoroutineScope()
    val drawerHaptics = rememberHaptics()
    fun deleteNow(c: Conversation) {
        drawerHaptics.tick()
        onDelete(c.id)
        undoScope.launch {
            undoBar.currentSnackbarData?.dismiss()
            val r = undoBar.showSnackbar("删除了「${c.title.take(12)}」", actionLabel = "撤销", duration = androidx.compose.material3.SnackbarDuration.Short)
            if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) onUndoDelete()
        }
    }
    var query by remember { mutableStateOf("") }
    var showAllProjects by remember { mutableStateOf(false) }
    // 置顶的对话（包括项目里的）放最上面；其余项目里的对话放在项目页面里，这里只列普通对话
    val pinned = state.conversations.filter { it.messages.isNotEmpty() && it.pinned }
    val chats = state.conversations.filter { it.messages.isNotEmpty() && it.projectId == null && !it.pinned }
    // 停手 250 毫秒再搜，放到后台线程，聊天多了打字也不卡
    val convs = state.conversations
    val results by androidx.compose.runtime.produceState(emptyList<Pair<Conversation, String>>(), query, convs) {
        if (query.isBlank()) { value = emptyList(); return@produceState }
        kotlinx.coroutines.delay(250)
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { searchConversations(convs, query) }
    }
    val colors = MaterialTheme.colorScheme
    // 写了一半没发的对话：像微信那样标一行红色的「草稿」
    val draftStore = remember { DraftStore(ctx) }
    val draftTick = DraftStore.changes
    fun chatRow(c: Conversation): @Composable () -> Unit = {
        val draft = remember(c.id, draftTick) { draftStore.get(c.id).replace(Regex("""\s+"""), " ").trim() }
        // 「⋯」只在当前对话上显示，其他的长按出菜单，列表看着清爽些
        ChatItem(c, c.id == state.currentId, { onSelect(c.id) }, { renaming = c }, { deleteNow(c) }, onPin = { onPin(c.id) },
            onMemory = { onMemory(c) }, showMore = c.id == state.currentId, answering = c.id in state.streamingIds, draft = draft)
    }
    ModalDrawerSheet(drawerContainerColor = colors.background, drawerShape = RoundedCornerShape(topEnd = 30.dp, bottomEnd = 30.dp)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 10.dp),
            ) {
                LogoMark(32.dp)
                Spacer(Modifier.size(10.dp))
                Text("Kavi", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            if (query.isNotBlank()) {
                LazyColumn(Modifier.weight(1f).padding(top = 4.dp)) {
                    item {
                        // 结果数跟着打字滚动变化
                        if (results.isEmpty()) SectionLabel("没有找到相关对话")
                        else Row(Modifier.padding(start = 28.dp, end = 16.dp, top = 22.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            val st = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                            Text("找到 ", style = st, color = colors.onSurfaceVariant)
                            RollingText(results.size, st, color = colors.onSurfaceVariant)
                            Text(" 个对话", style = st, color = colors.onSurfaceVariant)
                        }
                    }
                    items(results, key = { "s" + it.first.id }) { (c, snippet) ->
                        SearchResultRow(c.title, snippet, query, state.project(c.projectId)?.name) {
                            // 是消息里搜到的：打开后直接滚到那条，不用自己往上翻
                            ChatJump.target = searchHitId(c, query)?.let { c.id to it }
                            onSelect(c.id)
                            // 下次拉开侧边栏是平常的列表，不是上次搜的结果
                            query = ""
                        }
                    }
                }
            } else LazyColumn(Modifier.weight(1f)) {
                // 页面入口分两组：「底栏」里的就是输入框下面那排标签，「其他」的只在这里。长按拖动排顺序、换组（像 iOS 的选项卡）
                item {
                    val now = System.currentTimeMillis()
                    val due = state.cards.count { it.due <= now }
                    Box(Modifier.tourTarget("d-pages")) { TabArranger(
                        saved = state.settings.tabBar,
                        badge = { t ->
                            when (t) {
                                KaviTab.ROUTINES -> state.routines.count { it.enabled }.takeIf { it > 0 }?.toString()
                                KaviTab.REVIEW -> due.takeIf { it > 0 }?.toString()
                                else -> null
                            }
                        },
                        dot = { t -> t == KaviTab.MEMORY && state.dreams.any { !it.seen } },
                        onOpen = { t ->
                            when (t) {
                                KaviTab.CHAT -> onCloseDrawer()
                                KaviTab.LIBRARY -> onOpenLibrary()
                                KaviTab.MEMORY -> onOpenMemory()
                                KaviTab.BROWSER -> BrowserTabActivity.open(ctx)
                                KaviTab.ROUTINES -> onOpenRoutines()
                                KaviTab.FILES -> onOpenFiles()
                                KaviTab.SKILLS -> onOpenSkills()
                                KaviTab.APPS -> onOpenApps()
                                KaviTab.GAMES -> onOpenGames()
                                KaviTab.REVIEW -> onOpenReview()
                                KaviTab.TABLES -> onOpenTables()
                                KaviTab.DOWNLOADS -> onOpenDownloads()
                            }
                        },
                        onSave = onSaveTabs,
                        order = state.settings.drawerOrder,
                        onSaveOrder = onSaveOrder,
                    ) }
                }
                // 我的页面：放进侧边栏的小工具网页（文件删了的不显示）
                val pages = state.settings.pinnedPages.filter { com.jack.ai.data.PinnedPages.file(ctx, it) != null }
                if (pages.isNotEmpty()) {
                    item { SectionLabel("我的页面") }
                    items(pages, key = { "page$it" }) { rel -> PageRow(com.jack.ai.data.PinnedPages.title(rel)) { onOpenPage(rel) } }
                }
                if (pinned.isNotEmpty()) {
                    item { SectionLabel("置顶") }
                    items(pinned, key = { "pin" + it.id }) { c -> chatRow(c)() }
                }
                item { Box(Modifier.tourTarget("d-projects")) { SectionLabel("项目", action = "新建", onAction = onNewProject) } }
                // 项目多了只露出最近的 3 个，其余收起来，免得把聊天记录挤到下面去
                // 置顶的在前，其余按最近改过的
                val sortedProjects = state.projects.sortedByDescending { it.pinned }
                val shownProjects = if (showAllProjects) sortedProjects else sortedProjects.take(PROJECTS_SHOWN)
                items(shownProjects, key = { "p" + it.id }) { p ->
                    ProjectRow(
                        name = p.name + if (p.isolated) " 🔒" else "",
                        icon = p.icon,
                        selected = state.currentProjectId == p.id,
                        onClick = { onOpenProject(p.id) },
                    )
                }
                if (state.projects.size > PROJECTS_SHOWN) {
                    item {
                        DrawerRow(
                            icon = if (showAllProjects) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            label = if (showAllProjects) "收起" else "全部 ${state.projects.size} 个项目",
                            onClick = { showAllProjects = !showAllProjects },
                        )
                    }
                }
                if (state.projects.isEmpty()) {
                    item { DrawerRow(icon = Icons.Default.Add, label = "新建项目", tint = colors.primary, onClick = onNewProject) }
                }
                if (chats.isEmpty()) {
                    item {
                        SectionLabel("最近")
                        Text(
                            "还没有聊天记录",
                            color = colors.outline,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                        )
                    }
                }
                // 按时间分组：今天、昨天、7 天内……一眼就能找到
                chats.groupBy { dayGroup(it.updatedAt) }.forEach { (group, list) ->
                    item(key = "g$group") { SectionLabel(group) }
                    items(list, key = { it.id }) { c -> chatRow(c)() }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
            KaviSnackbarHost(undoBar)
            // 底部三件套：左边设置（填了名字就是名字的首字），中间搜索胶囊，右边新对话
            val name = state.settings.userName.trim()
            val haptics = rememberHaptics()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (name.isEmpty()) CircleIconButton(Icons.Default.Settings, "设置", Modifier.tourTarget("d-settings"), onClick = onSettings)
                else {
                    val settingsPress = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    Box(
                        Modifier.tourTarget("d-settings").pressScale(settingsPress, 0.9f).size(42.dp).glass(androidx.compose.foundation.shape.CircleShape)
                            .clickable(interactionSource = settingsPress, indication = androidx.compose.foundation.LocalIndication.current) { haptics.tick(); onSettings() },
                        contentAlignment = Alignment.Center,
                    ) { Text(name.take(1), style = MaterialTheme.typography.titleSmall, color = colors.onSurface) }
                }
                SearchField(query, { query = it }, Modifier.weight(1f).tourTarget("d-search"))
                CircleIconButton(AppIcons.NewChat, "新对话", onClick = onNew)
            }
        }
    }

    renaming?.let { c ->
        RenameDialog(c.title, onDismiss = { renaming = null }) { onRename(c.id, it); renaming = null }
    }
}

private const val PROJECTS_SHOWN = 3

/** 抽屉里的一个项目：文件夹图标加名字，不带框；当前项目有一层淡底。 */
/** 「我的页面」的一行：小工具图标加名字。 */
@Composable
private fun PageRow(name: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(Radii.m).clickable(onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Icon(AppIcons.Code, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.size(14.dp))
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ProjectRow(name: String, selected: Boolean, icon: String = "", onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .clip(Radii.m)
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // 项目选了图标就显示它，没选是文件夹
        if (icon.isNotBlank()) Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { Text(icon, fontSize = 18.sp) }
        else Icon(AppIcons.Folder, null, Modifier.size(24.dp), tint = colors.onSurface)
        Spacer(Modifier.size(14.dp))
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

/** 列表里的短时间：今天「14:05」、昨天「昨天」、今年「11月3日」、更早「2025/11/3」。不跟系统语言走，全中文。 */
internal fun shortWhen(at: Long, now: Long = System.currentTimeMillis()): String {
    val days = dayDiff(at, now)
    val same = { p: String -> java.text.SimpleDateFormat(p, java.util.Locale.CHINA).format(java.util.Date(at)) }
    return when {
        days <= 0 -> same("HH:mm")
        days == 1 -> "昨天"
        same("yyyy") == java.text.SimpleDateFormat("yyyy", java.util.Locale.CHINA).format(java.util.Date(now)) -> same("M月d日")
        else -> same("yyyy/M/d")
    }
}

private fun dayDiff(at: Long, now: Long): Int {
    fun start(ms: Long) = java.util.Calendar.getInstance().apply {
        timeInMillis = ms
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    return ((start(now) - start(at)) / 86_400_000L).toInt()
}

/** 对话按最后活动时间分组。 */
internal fun dayGroup(at: Long, now: Long = System.currentTimeMillis()): String {
    fun start(ms: Long) = java.util.Calendar.getInstance().apply {
        timeInMillis = ms
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val days = ((start(now) - start(at)) / 86_400_000L).toInt()
    return when {
        days <= 0 -> "今天"
        days == 1 -> "昨天"
        days < 7 -> "7 天内"
        days < 30 -> "30 天内"
        else -> java.text.SimpleDateFormat("yyyy年M月", java.util.Locale.CHINA).format(java.util.Date(at))
    }
}

@Composable
private fun SectionLabel(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 16.dp, top = 22.dp, bottom = 6.dp),
    ) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null && onAction != null) {
            Text(
                "+ $action",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(Radii.xs).clickable(onClick = onAction).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatItem(
    c: Conversation,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onPin: (() -> Unit)? = null,
    onMemory: (() -> Unit)? = null,
    showMore: Boolean = true,
    /** 这个对话正在后台写回答：标题后面转个小圈。 */
    answering: Boolean = false,
    /** 标题下面一行小字（项目页里放最后一条消息和时间）；空的就只有一行标题。 */
    subtitle: String? = null,
    /** 没发出去的草稿：标题下面一行红色「草稿」加开头几个字。 */
    draft: String = "",
) {
    var menu by remember { mutableStateOf(false) }
    var showUsage by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    // 选中的底色渐变过去；按住微微缩一下；长按震一下再出菜单
    val bg by androidx.compose.animation.animateColorAsState(if (selected) colors.secondaryContainer else colors.secondaryContainer.copy(alpha = 0f),
        Springs.defaultEffects(), label = "chatBg")
    Box(Modifier.padding(horizontal = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .pressScale(press, 0.98f)
                .clip(Radii.m)
                .background(bg)
                .combinedClickable(interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current,
                    onClick = onClick, onLongClick = { haptics.longPress(); menu = true })
                .heightIn(min = 48.dp)
                .padding(start = 16.dp, end = if (showMore) 4.dp else 16.dp, top = 2.dp, bottom = 2.dp),
        ) {
            // 置顶的图钉：弹着出来、缩着收走
            androidx.compose.animation.AnimatedVisibility(c.pinned,
                enter = androidx.compose.animation.scaleIn(Springs.fastSpatial(), initialScale = 0.3f) + androidx.compose.animation.fadeIn(Springs.fastEffects()),
                exit = androidx.compose.animation.scaleOut(targetScale = 0.3f) + androidx.compose.animation.fadeOut()) {
                Icon(AppIcons.Pin, null, Modifier.padding(end = 6.dp).size(14.dp), tint = colors.primary)
            }
            Column(Modifier.weight(1f).padding(vertical = if (subtitle.isNullOrBlank() && draft.isEmpty()) 10.dp else 8.dp)) {
                Text(
                    c.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) colors.onSecondaryContainer else colors.onSurface,
                )
                if (draft.isNotEmpty()) Text(buildAnnotatedString {
                    withStyle(SpanStyle(color = colors.error)) { append("[草稿] ") }
                    append(draft)
                }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp))
                else if (!subtitle.isNullOrBlank()) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
            if (answering) {
                // 「回答中」的小点慢慢一明一暗，看得出还在动
                val still = reduceMotion()
                val glow by androidx.compose.animation.core.rememberInfiniteTransition(label = "answering").animateFloat(0.35f, 1f,
                    androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(800), androidx.compose.animation.core.RepeatMode.Reverse), label = "dot")
                Box(Modifier.padding(start = 8.dp).size(7.dp).then(Modifier.graphicsLayer { alpha = if (still) 1f else glow })
                    .clip(androidx.compose.foundation.shape.CircleShape).background(Hue.BLUE.color))
                Text("回答中", style = MaterialTheme.typography.labelSmall, color = Hue.BLUE.color, modifier = Modifier.padding(start = 4.dp))
            }
            if (showMore) {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.MoreVert, "更多", Modifier.size(18.dp), tint = colors.outline)
                }
            }
        }
        RoundMenu(expanded = menu, onDismiss = { menu = false }) {
            if (onPin != null) MenuRow(if (c.pinned) "取消置顶" else "置顶", icon = AppIcons.Pin) { menu = false; onPin() }
            if (onMemory != null) MenuRow("记忆", icon = AppIcons.Moon) { menu = false; onMemory() }
            MenuRow("用量", icon = Icons.Default.Info) { menu = false; showUsage = true }
            MenuRow("重命名", icon = Icons.Default.Edit) { menu = false; onRename() }
            MenuRow("删除", icon = Icons.Default.Delete, danger = true) { menu = false; onDelete() }
        }
    }
    if (showUsage) ConversationUsageDialog(c) { showUsage = false }
}

/** 对话列表里露的那一行：最后一条有字的消息，去掉换行和 Markdown 记号。 */
internal fun preview(c: Conversation): String =
    c.messages.lastOrNull { it.content.isNotBlank() }?.content.orEmpty()
        .replace(Regex("""[#*`>|_]+"""), "").replace(Regex("""\s+"""), " ").trim().take(60)

/** 一个对话用了多少：请求次数、token、缓存命中、花费、当前上下文。 */
@Composable
private fun ConversationUsageDialog(c: Conversation, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    @Composable
    fun line(label: String, value: String) {
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(label, Modifier.weight(1f), color = colors.onSurfaceVariant)
            Text(value)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("用量 · ${c.title}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                if (c.requests == 0 && c.spentTokens == 0L) {
                    Text("还没有用量记录。", color = colors.onSurfaceVariant)
                } else {
                    if (c.requests > 0) {
                        line("请求次数", "${c.requests}")
                        line("输入", tokens(c.spentPrompt))
                        line("其中缓存命中", if (c.spentPrompt > 0) "${tokens(c.spentHit)}（${hitPercent(c.spentHit, c.spentPrompt)}）" else "0")
                        line("输出", tokens(c.spentCompletion))
                    }
                    line("合计 token", tokens(c.spentTokens))
                    line("花费", when {
                        c.spentCost > 0 -> yuan(c.spentCost) + if (c.unpriced) "（部分未计价）" else ""
                        c.unpriced -> "未计价"
                        c.requests == 0 -> "以前的没有记录"
                        else -> yuan(0.0)
                    })
                    if (c.lastPromptTokens > 0) line("当前上下文", tokens(c.lastPromptTokens.toLong()))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** 搜索命中的那条消息（最近的一条）；只有标题命中时返回 null。 */
internal fun searchHitId(c: Conversation, query: String): String? {
    val q = query.trim()
    if (q.isEmpty()) return null
    return c.messages.asReversed().firstOrNull { it.content.contains(q, ignoreCase = true) }?.id
}

/** 按标题和消息内容搜索对话，返回 (对话, 命中的一小段原文)。标题命中的排前面。 */
internal fun searchConversations(all: List<Conversation>, query: String): List<Pair<Conversation, String>> {
    val q = query.trim()
    val out = mutableListOf<Triple<Conversation, String, Int>>()
    for (c in all) {
        if (c.messages.isEmpty()) continue
        val inTitle = c.title.contains(q, ignoreCase = true)
        val hit = c.messages.asReversed().firstOrNull { it.content.contains(q, ignoreCase = true) }
        if (!inTitle && hit == null) continue
        val snippet = hit?.let { m ->
            val i = m.content.indexOf(q, ignoreCase = true)
            val start = (i - 18).coerceAtLeast(0)
            val end = (i + q.length + 40).coerceAtMost(m.content.length)
            (if (start > 0) "…" else "") + m.content.substring(start, end).replace('\n', ' ') + (if (end < m.content.length) "…" else "")
        } ?: ""
        out += Triple(c, snippet, if (inTitle) 0 else 1)
    }
    return out.sortedWith(compareBy<Triple<Conversation, String, Int>> { it.third }.thenByDescending { it.first.updatedAt })
        .take(100).map { it.first to it.second }
}

@Composable
internal fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, focus: androidx.compose.ui.focus.FocusRequester? = null,
                         placeholder: String = "搜索") {
    val colors = MaterialTheme.colorScheme
    Surface(shape = androidx.compose.foundation.shape.CircleShape, color = Color.Transparent, modifier = modifier.fillMaxWidth().glass(androidx.compose.foundation.shape.CircleShape)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
            Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
            Spacer(Modifier.size(10.dp))
            Box(Modifier.weight(1f).padding(vertical = 11.dp)) {
                if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.outline, maxLines = 1)
                val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    // 键盘上是「搜索」键：按了收起键盘，下面的结果整屏露出来
                    keyboardOptions = SearchKeyboard,
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { keyboard?.hide() }),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier.fillMaxWidth().let { if (focus != null) it.focusRequester(focus) else it },
                )
            }
            if (value.isNotEmpty()) {
                IconButton(onClick = { onChange("") }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, "清空", Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(title: String, snippet: String, query: String, project: String?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .clip(Radii.s)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(highlight(title, query, colors.primary), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium)
        val sub = listOfNotNull(project?.let { "项目：$it" }, snippet.takeIf { it.isNotEmpty() }).joinToString(" · ")
        if (sub.isNotEmpty()) {
            Text(highlight(sub, query, colors.primary), maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

/** 把命中的关键词标成主题色。 */
private fun highlight(text: String, query: String, color: Color): AnnotatedString = buildAnnotatedString {
    val q = query.trim()
    var i = 0
    while (q.isNotEmpty()) {
        val j = text.indexOf(q, i, ignoreCase = true)
        if (j < 0) break
        append(text.substring(i, j))
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) { append(text.substring(j, j + q.length)) }
        i = j + q.length
    }
    append(text.substring(i))
}

@Composable
private fun DrawerRow(
    icon: ImageVector,
    label: String,
    selected: Boolean = false,
    trailing: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .clip(Radii.s)
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
        Spacer(Modifier.size(14.dp))
        Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium, color = if (tint == colors.primary) tint else colors.onSurface)
        trailing?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = colors.primary)
        }
    }
}

/** App 的小标志：陶土色圆角方块里一个对话气泡。 */
@Composable
fun LogoMark(size: Dp) {
    // 和桌面图标一样：设置里选了哪个，这里就是哪个
    AppIconTile(LocalAppIcon.current, size)
}

@Composable
fun RenameDialog(initial: String, title: String = "重命名", onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    // 弹出来就全选好、键盘也起来：直接打新名字，回车就改好
    var field by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initial, androidx.compose.ui.text.TextRange(0, initial.length))) }
    val text = field.text
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = field, onValueChange = { field = it }, singleLine = true, modifier = Modifier.focusOnShow(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (text.isNotBlank()) onConfirm(text.trim()) }))
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 弹窗里的输入框：一弹出来就拿到焦点、键盘起来，不用再点一下输入框。 */
@Composable
internal fun Modifier.focusOnShow(): Modifier {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        // 等弹窗的窗口挂上再要焦点，太早要了键盘不出来
        kotlinx.coroutines.delay(120)
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    return this.then(Modifier.focusRequester(focus))
}

/** 填了东西的弹窗：点外面不再关掉（手一碰写了半天的就没了），返回键和「取消」照常能关。 */
internal fun keepIfEdited(edited: Boolean) = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = !edited)

/** 改一段已有文字：光标放在最后，接着写就行（直接给 String 时光标在开头）。 */
internal fun atEnd(text: String) = androidx.compose.ui.text.input.TextFieldValue(text, androidx.compose.ui.text.TextRange(text.length))

/**
 * 从网址起个名字：api.deepseek.com → Deepseek，mcp.notion.com/mcp → Notion。
 * 去掉 api、www、mcp 这类前缀和最后的域名后缀，取剩下最后一段；认不出来返回空。
 */
internal fun nameFromUrl(url: String): String {
    val host = runCatching { java.net.URI(url.trim()).host }.getOrNull()?.lowercase() ?: return ""
    if (host.isBlank() || host.all { it.isDigit() || it == '.' }) return ""
    val skip = setOf("api", "www", "mcp", "openai", "v1", "gateway", "server")
    val parts = host.split('.').filter { it.isNotBlank() }
    val core = (if (parts.size > 1) parts.dropLast(1) else parts).filterNot { it in skip }
    // 二级后缀（com.cn、co.uk 这类）：去掉以后剩下的最后一段才是名字
    val word = core.lastOrNull { it !in setOf("com", "co", "net", "org") } ?: return ""
    return word.replaceFirstChar { it.uppercase() }
}

/** 搜索框右边的「清空」：有字时才出现。 */
internal fun clearButton(value: String, onClear: () -> Unit): (@Composable () -> Unit)? =
    if (value.isEmpty()) null else ({
        IconButton(onClick = onClear) { Icon(Icons.Default.Close, "清空") }
    })

/** 单行输入框回车就提交（[ok] 为假时不动）。 */
internal fun doneAction(ok: Boolean, submit: () -> Unit) = androidx.compose.foundation.text.KeyboardActions(onDone = { if (ok) submit() })
internal val SearchKeyboard = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search)
internal val DoneKeyboard = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done)

/** 密钥框：默认打点，右边「显示 / 隐藏」，贴进去能看一眼有没有贴对。用法：`val (mask, eye) = secretBits()`。 */
@Composable
internal fun secretBits(): Pair<androidx.compose.ui.text.input.VisualTransformation, @Composable () -> Unit> {
    var show by remember { mutableStateOf(false) }
    val mask = if (show) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation()
    return mask to { androidx.compose.material3.TextButton(onClick = { show = !show }) { Text(if (show) "隐藏" else "显示") } }
}

@Composable
fun ConfirmDeleteDialog(title: String, body: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
