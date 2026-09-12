package com.nogirelay.app.blog

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.BlogMember
import com.nogirelay.app.data.BlogReadTracker
import com.nogirelay.app.data.BlogSummary
import com.nogirelay.app.translation.BlogTranslationLayout
import com.nogirelay.app.translation.BlogTranslationManager
import com.nogirelay.app.ui.RemoteImage
import kotlinx.coroutines.launch

private const val BLOG_PAGE_SIZE = 20

@Composable
fun BlogScreen(
    refreshKey: Int,
    initialBlogId: String?,
    onInitialBlogHandled: (String) -> Unit,
    onUnreadChanged: () -> Unit,
) {
    var selectedBlogId by remember { mutableStateOf<String?>(null) }
    var selectedMemberIds by remember { mutableStateOf<Set<String>?>(null) }
    var oldestFirst by remember { mutableStateOf(false) }
    var showMemberDialog by remember { mutableStateOf(false) }
    var currentPage by remember { mutableIntStateOf(0) }
    var pageInput by remember { mutableStateOf("1") }
    var showPageDialog by remember { mutableStateOf(false) }

    LaunchedEffect(initialBlogId) {
        initialBlogId?.let { selectedBlogId = it }
    }

    val selected = selectedBlogId
    BackHandler(enabled = selected != null) { selectedBlogId = null }
    val members = remember(refreshKey) { AppGraph.database.blogMembers() }
    LaunchedEffect(members) {
        selectedMemberIds?.let { selectedIds ->
            val availableIds = members.mapTo(mutableSetOf(), BlogMember::id)
            val updated = selectedIds.intersect(availableIds)
            if (updated != selectedIds) {
                selectedMemberIds = updated
                currentPage = 0
                pageInput = "1"
            }
        }
    }

    val matchingCount = remember(refreshKey, selectedMemberIds) {
        AppGraph.database.countBlogs(selectedMemberIds)
    }
    val totalCount = remember(refreshKey) { AppGraph.database.countBlogs() }
    val totalPages = ((matchingCount + BLOG_PAGE_SIZE - 1) / BLOG_PAGE_SIZE).coerceAtLeast(1)
    val page = currentPage.coerceIn(0, totalPages - 1)
    val blogs = remember(refreshKey, selectedMemberIds, oldestFirst, page) {
        AppGraph.database.blogSummaries(
            memberIds = selectedMemberIds,
            oldestFirst = oldestFirst,
            limit = BLOG_PAGE_SIZE,
            offset = page * BLOG_PAGE_SIZE,
        )
    }

    fun goToPage(targetPage: Int) {
        val safePage = targetPage.coerceIn(0, totalPages - 1)
        currentPage = safePage
        pageInput = (safePage + 1).toString()
    }

    LaunchedEffect(selectedMemberIds, oldestFirst) {
        currentPage = 0
        pageInput = "1"
    }
    LaunchedEffect(page, totalPages) {
        if (currentPage != page) currentPage = page
        pageInput = (page + 1).toString()
    }

    if (showMemberDialog) {
        BlogMemberFilterDialog(
            members = members,
            selectedIds = selectedMemberIds ?: members.mapTo(linkedSetOf(), BlogMember::id),
            onDismiss = { showMemberDialog = false },
            onConfirm = { selectedIds ->
                val allIds = members.mapTo(linkedSetOf(), BlogMember::id)
                selectedMemberIds = selectedIds.takeUnless { it == allIds }
                currentPage = 0
                pageInput = "1"
                showMemberDialog = false
            },
        )
    }
    if (showPageDialog) {
        BlogPageDialog(
            pageInput = pageInput,
            totalPages = totalPages,
            onInputChange = { pageInput = it },
            onDismiss = { showPageDialog = false },
            onConfirm = { requestedPage ->
                goToPage(requestedPage - 1)
                showPageDialog = false
            },
        )
    }

    Crossfade(
        targetState = selected,
        animationSpec = tween(durationMillis = 220),
        label = "blog-detail-transition",
    ) { selectedId ->
        if (selectedId != null) {
            BlogDetail(
                blogId = selectedId,
                refreshKey = refreshKey,
                onBack = { selectedBlogId = null },
                onUnreadChanged = onUnreadChanged,
            )
            LaunchedEffect(selectedId, initialBlogId) {
                if (selectedId == initialBlogId) onInitialBlogHandled(selectedId)
            }
            return@Crossfade
        }

    Column(Modifier.fillMaxSize()) {
        Text(
            "博客",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        ) {
            BlogSortSwitcher(
                oldestFirst = oldestFirst,
                onOldestFirstChanged = { oldestFirst = it },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showMemberDialog = true }) {
                Icon(Icons.Rounded.FilterList, contentDescription = "筛选成员")
            }
        }

        if (blogs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Article, contentDescription = null, modifier = Modifier.size(52.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(if (totalCount == 0) "正在等待博客同步" else "当前成员筛选下没有博客")
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize().padding(top = 10.dp),
            ) {
                items(blogs, key = BlogSummary::id) { blog ->
                    BlogSummaryCard(blog, onClick = { selectedBlogId = blog.id })
                }
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text("$matchingCount 篇博客", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            OutlinedButton(onClick = { goToPage(page - 1) }, enabled = page > 0, modifier = Modifier.weight(1f)) { Text("上一页", maxLines = 1) }
                            OutlinedButton(
                                onClick = {
                                    pageInput = (page + 1).toString()
                                    showPageDialog = true
                                },
                                enabled = matchingCount > 0,
                                modifier = Modifier.weight(1.25f),
                            ) {
                                Text("${page + 1} / $totalPages", maxLines = 1, fontSize = 12.sp)
                                Icon(Icons.Rounded.ArrowDropDown, contentDescription = "选择页码")
                            }
                            OutlinedButton(onClick = { goToPage(page + 1) }, enabled = page < totalPages - 1, modifier = Modifier.weight(1f)) { Text("下一页", maxLines = 1) }
                        }
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
    }
}

@Composable
private fun BlogSortSwitcher(
    oldestFirst: Boolean,
    onOldestFirstChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val tabWidth = maxWidth / 2
        val indicatorOffset by animateDpAsState(
            targetValue = if (oldestFirst) tabWidth else 0.dp,
            animationSpec = tween(durationMillis = 220),
            label = "blog-sort-indicator",
        )
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .align(Alignment.CenterStart)
            .offset(x = indicatorOffset)
            .padding(3.dp)
            .width(tabWidth)
            .fillMaxHeight(),
    ) {
        Box(Modifier.fillMaxSize())
    }
        Row(Modifier.fillMaxSize()) {
            BlogSortTab("最新", selected = !oldestFirst, onClick = { onOldestFirstChanged(false) }, modifier = Modifier.weight(1f))
            BlogSortTab("最早", selected = oldestFirst, onClick = { onOldestFirstChanged(true) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun BlogSortTab(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun BlogMemberFilterDialog(
    members: List<BlogMember>,
    selectedIds: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var draft by remember(members, selectedIds) { mutableStateOf(selectedIds.toSet()) }
    val groups = remember(members) { members.groupBy(BlogMember::category) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择成员") },
        text = {
            Column {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 430.dp),
                ) {
                    groups.forEach { (category, groupMembers) ->
                        item(key = "category-$category", span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                category,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp),
                            )
                        }
                        gridItems(groupMembers, key = BlogMember::id) { member ->
                            Surface(
                                onClick = {
                                    draft = if (member.id in draft) draft - member.id else draft + member.id
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = if (member.id in draft) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(92.dp),
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 8.dp),
                                ) {
                                    RemoteImage(
                                        url = member.avatarUrl,
                                        contentDescription = member.name,
                                            loadCachedImmediately = false,
                                        modifier = Modifier.size(46.dp).clip(CircleShape),
                                    )
                                    Text(
                                        member.name,
                                        maxLines = 1,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(top = 5.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { draft = members.mapTo(linkedSetOf(), BlogMember::id) }) {
                    Icon(Icons.Rounded.DoneAll, contentDescription = "全部选择")
                }
                IconButton(onClick = { draft = emptySet() }) {
                    Icon(Icons.Rounded.ClearAll, contentDescription = "全部清除")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(onClick = { onConfirm(draft) }) { Text("确定") }
            }
        },
        dismissButton = {},
    )
}

@Composable
private fun BlogPageDialog(
    pageInput: String,
    totalPages: Int,
    onInputChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val requestedPage = pageInput.toIntOrNull()
    val canJump = requestedPage != null && requestedPage in 1..totalPages
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("跳转") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("请输入1-${totalPages}之间的页码", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = pageInput,
                    onValueChange = { onInputChange(it.filter(Char::isDigit).take(6)) },
                    singleLine = true,
                    label = { Text("页码") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (canJump) onConfirm(requestedPage!!) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(requestedPage!!) }, enabled = canJump) { Text("跳转") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun BlogSummaryCard(blog: BlogSummary, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Column {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 2.dp),
                    ) {
                        RemoteImage(
                            url = blog.memberAvatarUrl,
                            contentDescription = blog.memberName,
                            loadCachedImmediately = true,
                            modifier = Modifier.size(38.dp).clip(CircleShape),
                        )
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(blog.memberName, fontWeight = FontWeight.SemiBold)
                            Text(formatBlogDate(blog.publishedAt), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (blog.isUnread) Badge { Text("未读") }
                }
                Spacer(Modifier.height(10.dp))
                Text(blog.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                blog.translatedTitle?.takeIf { it != blog.title }?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
            blog.imageUrl?.let {
                RemoteImage(
                    url = it,
                    contentDescription = blog.title,
                    contentScale = ContentScale.Fit,
                    preserveAspectRatio = true,
                    loadCachedImmediately = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            }
        }
    }
}

@Composable
private fun BlogDetail(blogId: String, refreshKey: Int, onBack: () -> Unit, onUnreadChanged: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var localRefresh by remember { mutableIntStateOf(0) }
    val blog = remember(blogId, refreshKey, localRefresh) { AppGraph.database.findBlog(blogId) }
    val settings = remember(refreshKey) { AppGraph.settings.read() }

    DisposableEffect(blogId) {
        BlogReadTracker.openBlog(blogId)
        onDispose { BlogReadTracker.closeBlog(blogId) }
    }
    LaunchedEffect(blogId) {
        if (AppGraph.database.markBlogRead(blogId) > 0) {
            localRefresh++
            onUnreadChanged()
        }
    }
    LaunchedEffect(blog?.bodyHtml, settings.translationEnabled, settings.aiModel) {
        val current = blog ?: return@LaunchedEffect
        if (current.bodyHtml.isNotBlank() && settings.translationEnabled && !current.translationDone) {
            BlogTranslationManager.enqueue(context, current.id)
        }
    }

    if (blog == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("正在同步博客…") }
        return
    }
    val contentBlocks = remember(blog.bodyHtml) { BlogContentParser.blocks(blog.bodyHtml) }
    val bodyText = remember(contentBlocks) { BlogContentParser.plainText(contentBlocks) }
    val source = remember(blog.title, bodyText) {
        listOf(blog.title.trim(), bodyText).filter(String::isNotBlank).joinToString("\n\n\n")
    }
    val layout = remember(source) { BlogTranslationLayout.from(source) }
    val translations = remember(blog.translation, source) { layout.decode(blog.translation) }
    val titleTranslation = if (blog.title.isNotBlank()) translations.firstOrNull() else null
    val bodyTranslations = if (blog.title.isNotBlank()) translations.drop(1) else translations
    val displayBlocks = remember(contentBlocks, bodyTranslations) { displayBlocks(contentBlocks, bodyTranslations) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回博客列表")
                }
                Text("博客", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 2.dp),
                    ) {
                        RemoteImage(
                            url = blog.memberAvatarUrl,
                            contentDescription = blog.memberName,
                            loadCachedImmediately = true,
                            modifier = Modifier.size(44.dp).clip(CircleShape),
                        )
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(blog.memberName, fontWeight = FontWeight.SemiBold)
                            Text(formatBlogDate(blog.publishedAt), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (settings.translationEnabled && blog.bodyHtml.isNotBlank()) {
                        IconButton(onClick = {
                            BlogTranslationManager.enqueue(context, blog.id, force = true)
                        }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "重新翻译")
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(blog.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                titleTranslation?.takeIf(String::isNotBlank)?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (blog.bodyHtml.isBlank()) {
                    Text("正在从乃木坂46官网同步正文…", modifier = Modifier.padding(top = 18.dp))
                }
            }
        }
        items(displayBlocks) { block ->
            when (block) {
                is DisplayBlock.Image -> RemoteImage(
                    url = block.url,
                    contentDescription = blog.title,
                    contentScale = ContentScale.Fit,
                    preserveAspectRatio = true,
                    loadCachedImmediately = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
                is DisplayBlock.Paragraph -> Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SelectionContainer { Text(block.original, lineHeight = 24.sp) }
                    block.translation?.takeIf(String::isNotBlank)?.let {
                        SelectionContainer {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 15.sp,
                                modifier = Modifier.padding(top = 7.dp),
                            )
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

private sealed interface DisplayBlock {
    data class Paragraph(val original: String, val translation: String?) : DisplayBlock
    data class Image(val url: String) : DisplayBlock
}

private fun displayBlocks(blocks: List<BlogContentBlock>, translations: List<String>): List<DisplayBlock> {
    var translationIndex = 0
    return buildList {
        blocks.forEach { block ->
            when (block) {
                is BlogContentBlock.Image -> add(DisplayBlock.Image(block.url))
                is BlogContentBlock.Text -> BlogContentParser.paragraphs(block.value).forEach { paragraph ->
                    add(DisplayBlock.Paragraph(paragraph, translations.getOrNull(translationIndex)))
                    translationIndex += 1
                }
            }
        }
    }
}

private fun formatBlogDate(value: String): String = value
    .replace('T', ' ')
    .removeSuffix("+09:00")
