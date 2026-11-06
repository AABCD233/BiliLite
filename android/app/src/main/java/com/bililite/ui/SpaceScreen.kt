package com.bililite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.bililite.core.C
import com.bililite.data.Up
import com.bililite.data.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * UP 空间页(v0.6.0)
 *
 * 参考 PiliPlus 的 MemberPage 布局,用 Compose 重新实现(项目是 Dart,代码不能直接拷):
 *  - 顶部资料区:头像/昵称/LV/签名/粉丝/关注/获赞/UID + 关注按钮
 *  - 四个 tab:主页 / 动态 / 投稿 / 收藏
 *  - 主页:视频网格 + 排序(最新发布/最多播放/最多收藏) + 播放全部
 *  - 收藏:该 UP 的公开收藏夹列表,点进去看收藏的视频
 *
 * 数据全部走 BiliApi 新加的 space* 接口;任何解析失败都只显示空态,不能崩主界面。
 */

private data class SpaceProfile(
    val mid: String,
    val name: String,
    val face: String,
    val sign: String,
    val level: Int,
    val fans: Long,
    val following: Long,
    val likes: Long
)

private data class SpaceDyn(
    val id: String,
    val pubTs: Long,
    val text: String,
    val images: List<String>,
    val bvid: String,
    val title: String,
    val cover: String,
    val duration: String
)

private data class SpaceFavFolder(val id: Long, val title: String, val count: Int)

/** 合集 / 系列(投稿子 tab 用)。season=true 表示视频合集,false 表示系列。 */
private data class SpaceCollection(
    val id: Long, val title: String, val cover: String, val total: Int, val season: Boolean
)

@Composable
fun SpaceScreen(
    vm: BiliViewModel,
    mid: String,
    fallback: Up? = null,
    onBack: () -> Unit,
    onPlay: (Video, List<Video>) -> Unit
) {
    val api = vm.api
    val scope = rememberCoroutineScope()
    val midLong = mid.toLongOrNull() ?: 0L

    var profile by remember(mid) {
        mutableStateOf(
            fallback?.let {
                SpaceProfile(it.id, it.name, it.face, "", 0, it.fans, 0, 0)
            }
        )
    }
    var tab by remember { mutableStateOf(0) }
    var loadingProfile by remember { mutableStateOf(true) }
    var profileError by remember { mutableStateOf("") }

    // 视频
    var videos by remember(mid) { mutableStateOf<List<Video>>(emptyList()) }
    var total by remember(mid) { mutableStateOf(0) }
    var page by remember(mid) { mutableStateOf(1) }
    var loadingVideos by remember { mutableStateOf(false) }
    var videosError by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(0) } // 0 最新发布 1 最多播放 2 最多收藏

    // 动态
    var dyns by remember(mid) { mutableStateOf<List<SpaceDyn>>(emptyList()) }
    var dynOffset by remember { mutableStateOf("") }
    var dynHasMore by remember { mutableStateOf(false) }
    var loadingDyn by remember { mutableStateOf(false) }
    var dynError by remember { mutableStateOf("") }

    // 收藏
    var folders by remember(mid) { mutableStateOf<List<SpaceFavFolder>>(emptyList()) }
    var folderError by remember { mutableStateOf("") }
    var loadingFolders by remember { mutableStateOf(false) }
    var openFolder by remember { mutableStateOf<SpaceFavFolder?>(null) }
    var folderVideos by remember { mutableStateOf<List<Video>>(emptyList()) }
    var loadingFolderVideos by remember { mutableStateOf(false) }

    // 投稿子 tab:0 视频 1 合集 2 系列
    var collSub by remember { mutableStateOf(0) }
    var seasons by remember(mid) { mutableStateOf<List<SpaceCollection>>(emptyList()) }
    var series by remember(mid) { mutableStateOf<List<SpaceCollection>>(emptyList()) }
    var collLoaded by remember { mutableStateOf(false) }
    var collLoading by remember { mutableStateOf(false) }
    var collError by remember { mutableStateOf("") }
    var openColl by remember { mutableStateOf<SpaceCollection?>(null) }
    var collVideos by remember { mutableStateOf<List<Video>>(emptyList()) }
    var collVideosLoading by remember { mutableStateOf(false) }

    // 关注状态:0 未知/未登录(隐藏按钮),1 未关注,2 已关注
    var followState by remember { mutableStateOf(0) }
    var followBusy by remember { mutableStateOf(false) }

    suspend fun loadProfile() {
        loadingProfile = true
        profileError = ""
        try {
            val info = withContext(Dispatchers.IO) {
                runCatching { api.spaceInfo(midLong) }.getOrNull()
            }
            val d = info?.optJSONObject("data")
            if (d != null) {
                profile = SpaceProfile(
                    mid = mid,
                    name = d.optString("name", profile?.name ?: "UP主"),
                    face = d.optString("face", profile?.face ?: ""),
                    sign = d.optString("sign", ""),
                    level = d.optInt("level", 0),
                    fans = profile?.fans ?: 0L,
                    following = profile?.following ?: 0L,
                    likes = profile?.likes ?: 0L
                )
            }
            // 粉丝/关注(免登录可用)
            val stat = withContext(Dispatchers.IO) {
                runCatching { api.relationStat(midLong) }.getOrNull()
            }?.optJSONObject("data")
            if (stat != null) {
                profile = profile?.copy(
                    fans = stat.optLong("follower", 0L),
                    following = stat.optLong("following", 0L)
                )
            }
            // 获赞(可能为空对象,容错)
            val upstat = withContext(Dispatchers.IO) {
                runCatching { api.spaceUpStat(midLong) }.getOrNull()
            }?.optJSONObject("data")
            val likes = upstat?.optLong("likes", 0L) ?: 0L
            if (likes > 0) profile = profile?.copy(likes = likes)

            // 关注状态:acc/info 有 is_followed 时直接用,避免多打一次接口
            // (未登录 code=-101 → 隐藏按钮)
            if (d != null && d.has("is_followed")) {
                followState = if (d.optBoolean("is_followed", false)) 2 else 1
            } else {
                val rel = withContext(Dispatchers.IO) {
                    runCatching { api.relation(midLong) }.getOrNull()
                }
                if (rel != null && rel.optInt("code", -1) == 0) {
                    val attr = rel.optJSONObject("data")?.optInt("attribute", 0) ?: 0
                    followState = if (attr == 2 || attr == 6) 2 else 1
                } else {
                    followState = 0
                }
            }
            if (profile == null) profileError = "资料加载失败"
        } catch (e: Exception) {
            profileError = "资料加载失败: ${e.message ?: ""}"
        } finally {
            loadingProfile = false
        }
    }

    suspend fun loadVideos(reset: Boolean, sortMode: Int = sort) {
        if (loadingVideos) return
        loadingVideos = true
        videosError = ""
        try {
            val pn = if (reset) 1 else page
            val order = when (sortMode) { 1 -> "click"; 2 -> "stow"; else -> "pubdate" }
            var j = withContext(Dispatchers.IO) {
                runCatching { api.spaceVideos(midLong, pn, order) }.getOrNull()
            }
            if (j == null || j.optInt("code", -1) != 0) {
                // wbi 被风控时退回非 wbi 接口(只支持按发布时间)
                j = withContext(Dispatchers.IO) {
                    runCatching { api.spaceVideosFallback(midLong, pn) }.getOrNull()
                }
            }
            val (list, count) = parseSpaceVideos(j)
            if (count > 0) total = count
            videos = if (reset) list else (videos + list).distinctBy { it.id }
            page = pn + 1
            if (list.isEmpty() && videos.isEmpty()) videosError = "暂无投稿"
        } catch (e: Exception) {
            videosError = "加载失败: ${e.message ?: ""}"
        } finally {
            loadingVideos = false
        }
    }

    suspend fun loadDynamics(more: Boolean) {
        if (loadingDyn) return
        loadingDyn = true
        dynError = ""
        try {
            val j = withContext(Dispatchers.IO) {
                runCatching { api.spaceDynamics(midLong, if (more) dynOffset else "") }.getOrNull()
            }
            val data = j?.optJSONObject("data")
            if (j == null || j.optInt("code", -1) != 0 || data == null) {
                dynError = "动态加载失败"
                return
            }
            val arr = data.optJSONArray("items") ?: return
            val add = (0 until arr.length()).mapNotNull { i ->
                parseDynamic(arr.optJSONObject(i))
            }
            dyns = if (more) dyns + add else add
            dynOffset = data.optString("offset", "")
            dynHasMore = data.optBoolean("has_more", false) && dynOffset.isNotEmpty()
            if (dyns.isEmpty()) dynError = "暂无动态"
        } catch (e: Exception) {
            dynError = "动态加载失败: ${e.message ?: ""}"
        } finally {
            loadingDyn = false
        }
    }

    suspend fun loadFolders() {
        if (loadingFolders) return
        loadingFolders = true
        folderError = ""
        try {
            val j = withContext(Dispatchers.IO) {
                runCatching { api.favFoldersOf(midLong) }.getOrNull()
            }
            val list = j?.optJSONObject("data")?.optJSONArray("list")
            if (j == null || j.optInt("code", -1) != 0 || list == null) {
                folderError = "该 UP 未公开收藏夹(或需登录)"
                return
            }
            folders = (0 until list.length()).mapNotNull { i ->
                val o = list.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optLong("id", 0L)
                if (id <= 0) null else SpaceFavFolder(
                    id, o.optString("title", "收藏夹"), o.optInt("media_count", 0))
            }
            if (folders.isEmpty()) folderError = "没有公开的收藏夹"
        } catch (e: Exception) {
            folderError = "收藏夹加载失败: ${e.message ?: ""}"
        } finally {
            loadingFolders = false
        }
    }

    suspend fun loadFolderVideos(f: SpaceFavFolder) {
        loadingFolderVideos = true
        folderVideos = emptyList()
        try {
            val j = withContext(Dispatchers.IO) {
                runCatching { api.favResourceList(f.id, 1) }.getOrNull()
            }
            folderVideos = parseFavVideos(j)
        } catch (_: Exception) {
            folderVideos = emptyList()
        } finally {
            loadingFolderVideos = false
        }
    }

    suspend fun loadCollections() {
        if (collLoading) return
        collLoading = true; collError = ""
        try {
            val j = withContext(Dispatchers.IO) {
                runCatching { api.seasonsSeriesList(midLong) }.getOrNull()
            }
            val lists = j?.optJSONObject("data")?.optJSONObject("items_lists")
            if (j == null || j.optInt("code", -1) != 0 || lists == null) {
                collError = "合集加载失败"
                return
            }
            seasons = parseCollections(lists.optJSONArray("seasons_list"), true)
            series = parseCollections(lists.optJSONArray("series_list"), false)
            collLoaded = true
            if (seasons.isEmpty() && series.isEmpty()) collError = "该 UP 暂无合集/系列"
        } catch (e: Exception) {
            collError = "合集加载失败: ${e.message ?: ""}"
        } finally {
            collLoading = false
        }
    }

    suspend fun loadCollectionVideos(c: SpaceCollection) {
        collVideosLoading = true
        collVideos = emptyList()
        try {
            val j = withContext(Dispatchers.IO) {
                runCatching {
                    if (c.season) api.seasonArchivesList(c.id, 1)
                    else api.seriesArchivesList(c.id, 1)
                }.getOrNull()
            }
            collVideos = parseArchives(j)
        } catch (_: Exception) {
            collVideos = emptyList()
        } finally {
            collVideosLoading = false
        }
    }

    LaunchedEffect(mid) {
        loadProfile()
        loadVideos(reset = true)
    }
    LaunchedEffect(tab) {
        // tab: 0 主页 / 1 动态 / 2 投稿 / 3 收藏
        when (tab) {
            1 -> if (dyns.isEmpty() && !loadingDyn) loadDynamics(false)
            2 -> if (!collLoaded && !collLoading) loadCollections()
            3 -> if (folders.isEmpty() && !loadingFolders) loadFolders()
        }
    }

    BackHandler { onBack() }

    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
    val cols = if (wide) 4 else 2

    Column(Modifier.fillMaxSize().background(C.bg)) {
        // 顶栏
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回", color = C.t1) }
            Text(profile?.name ?: "", color = C.t1, fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton(onClick = { scope.launch { loadProfile(); loadVideos(reset = true) } }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = C.t1,
                    modifier = Modifier.size(20.dp))
            }
        }

        // 资料区
        SpaceHeader(
            profile = profile,
            followState = followState,
            followBusy = followBusy,
            onToggleFollow = {
                if (followState != 0) {
                followBusy = true
                scope.launch {
                    try {
                        val act = if (followState == 2) 2 else 1
                        val r = withContext(Dispatchers.IO) {
                            runCatching { api.modifyRelation(midLong, act) }.getOrNull()
                        }
                        if (r != null && r.optInt("code", -1) == 0) {
                            followState = if (act == 1) 2 else 1
                            profile = profile?.copy(fans = (profile?.fans ?: 0L) + if (act == 1) 1 else -1)
                        }
                    } finally { followBusy = false }
                }
                }
            }
        )

        // tabs
        val tabs = listOf("主页", "动态", "投稿", "收藏")
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            tabs.forEachIndexed { i, t ->
                val on = tab == i
                Column(Modifier.weight(1f).clickable { tab = i },
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t, color = if (on) C.t1 else C.t2, fontSize = 15.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(vertical = 10.dp))
                    Box(Modifier.fillMaxWidth().height(2.dp)
                        .background(if (on) C.block else Color.Transparent))
                }
            }
        }

        if (profile == null && loadingProfile && profileError.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = C.t1, modifier = Modifier.size(28.dp))
            }
            return@Column
        }

        when (tab) {
            0 -> VideosTab(
                cols = cols, videos = videos, total = total, sort = sort,
                onSort = { s ->
                    sort = s; videos = emptyList(); page = 1; total = 0
                    scope.launch { loadVideos(reset = true, sortMode = s) }
                },
                loading = loadingVideos, error = videosError,
                onPlay = onPlay,
                onLoadMore = { scope.launch { loadVideos(reset = false) } }
            )
            1 -> DynamicsTab(dyns, loadingDyn, dynError, dynHasMore,
                onLoadMore = { scope.launch { loadDynamics(true) } })
            2 -> ContributeTab(
                cols = cols, videos = videos, total = total, sort = sort,
                onSort = { s ->
                    sort = s; videos = emptyList(); page = 1; total = 0
                    scope.launch { loadVideos(reset = true, sortMode = s) }
                },
                loading = loadingVideos, error = videosError,
                onPlay = onPlay,
                onLoadMore = { scope.launch { loadVideos(reset = false) } },
                sub = collSub, onSub = { collSub = it; openColl = null; collVideos = emptyList() },
                seasons = seasons, series = series,
                collLoading = collLoading, collError = collError,
                openColl = openColl, collVideos = collVideos,
                collVideosLoading = collVideosLoading,
                onOpenColl = { c -> openColl = c; scope.launch { loadCollectionVideos(c) } },
                onCloseColl = { openColl = null; collVideos = emptyList() }
            )
            else -> FavoritesTab(
                folders = folders, error = folderError, loading = loadingFolders,
                openFolderItem = openFolder, videos = folderVideos, loadingVideos = loadingFolderVideos,
                cols = cols, onPlay = onPlay,
                onOpen = { f -> openFolder = f; scope.launch { loadFolderVideos(f) } },
                onClose = { openFolder = null; folderVideos = emptyList() }
            )
        }
    }
}

@Composable
private fun SpaceHeader(
    profile: SpaceProfile?,
    followState: Int,
    followBusy: Boolean,
    onToggleFollow: () -> Unit
) {
    if (profile == null) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (profile.face.isNotEmpty()) {
                AsyncImage(model = profile.face, contentDescription = profile.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp).clip(CircleShape).background(C.line))
            } else {
                Box(Modifier.size(64.dp).clip(CircleShape).background(C.line),
                    contentAlignment = Alignment.Center) {
                    Text(profile.name.take(1), color = C.t2, fontSize = 24.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(profile.name, color = C.t1, fontSize = 18.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    if (profile.level > 0) {
                        Spacer(Modifier.width(6.dp))
                        Text("LV${profile.level}", color = C.t2, fontSize = 10.sp,
                            modifier = Modifier
                                .background(C.soft, RoundedCornerShape(4.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp))
                    }
                }
                if (profile.sign.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(profile.sign, color = C.t2, fontSize = 12.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SpaceStat("粉丝", profile.fans)
            SpaceStat("关注", profile.following)
            SpaceStat("获赞", profile.likes)
            Spacer(Modifier.weight(1f))
            if (followState != 0) {
                val followed = followState == 2
                OutlinedButton(onClick = onToggleFollow, enabled = !followBusy,
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (followed) C.soft else Color.Transparent)) {
                    Text(if (followed) "已关注" else "关注", color = C.t1, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("UID: ${profile.mid}", color = C.t2, fontSize = 11.sp)
    }
}

@Composable
private fun SpaceStat(label: String, value: Long) {
    Column(Modifier.padding(end = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(fmtCount(value), color = C.t1, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = C.t2, fontSize = 11.sp)
    }
}

@Composable
private fun VideosTab(
    cols: Int,
    videos: List<Video>,
    total: Int,
    sort: Int,
    onSort: (Int) -> Unit,
    loading: Boolean,
    error: String,
    onPlay: (Video, List<Video>) -> Unit,
    onLoadMore: () -> Unit
) {
    var sortOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (total > 0) "共 $total 视频" else "共 ${videos.size} 视频",
                color = C.t2, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            if (videos.isNotEmpty()) {
                TextButton(onClick = { onPlay(videos.first(), videos) }) {
                    Text("▶ 播放全部", color = C.t1, fontSize = 12.sp)
                }
            }
            Box {
                TextButton(onClick = { sortOpen = true }) {
                    Text((if (sort == 1) "最多播放" else if (sort == 2) "最多收藏" else "最新发布") + " ▾",
                        color = C.t1, fontSize = 12.sp)
                }
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    listOf("最新发布", "最多播放", "最多收藏").forEachIndexed { i, label ->
                        DropdownMenuItem(
                            text = { Text(label, color = if (i == sort) C.t1 else C.t2, fontSize = 13.sp) },
                            onClick = { sortOpen = false; if (i != sort) onSort(i) })
                    }
                }
            }
        }
        when {
            loading && videos.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = C.t1, modifier = Modifier.size(26.dp))
            }
            videos.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error.ifBlank { "暂无投稿" }, color = C.t2, fontSize = 13.sp)
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(cols),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                gridItems(videos, key = { it.id }) { v ->
                    SpaceVideoCard(v) { onPlay(v, videos) }
                }
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                        if (loading) CircularProgressIndicator(color = C.t1, modifier = Modifier.size(20.dp))
                        else TextButton(onClick = onLoadMore) {
                            Text("加载更多", color = C.t2, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContributeTab(
    cols: Int,
    videos: List<Video>,
    total: Int,
    sort: Int,
    onSort: (Int) -> Unit,
    loading: Boolean,
    error: String,
    onPlay: (Video, List<Video>) -> Unit,
    onLoadMore: () -> Unit,
    sub: Int,
    onSub: (Int) -> Unit,
    seasons: List<SpaceCollection>,
    series: List<SpaceCollection>,
    collLoading: Boolean,
    collError: String,
    openColl: SpaceCollection?,
    collVideos: List<Video>,
    collVideosLoading: Boolean,
    onOpenColl: (SpaceCollection) -> Unit,
    onCloseColl: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        // 投稿子 tab:视频 / 合集 / 系列(PiliPlus 同款层级)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("视频", "合集", "系列").forEachIndexed { i, label ->
                val on = sub == i
                Surface(color = if (on) C.block else C.card,
                    shape = RoundedCornerShape(16.dp),
                    border = if (on) null else androidx.compose.foundation.BorderStroke(1.dp, C.line),
                    modifier = Modifier.clickable { onSub(i) }) {
                    Text(label, fontSize = 12.sp, color = if (on) C.onBlock else C.t2,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            if (openColl != null) {
                Text(openColl.title, color = C.t2, fontSize = 11.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }

        if (sub == 0) {
            VideosTab(cols, videos, total, sort, onSort, loading, error, onPlay, onLoadMore)
            return@Column
        }

        // 合集/系列详情
        if (openColl != null) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onCloseColl) {
                        Text("← ${if (openColl.season) "合集" else "系列"}", color = C.t1, fontSize = 13.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Text("${openColl.total} 个视频", color = C.t2, fontSize = 11.sp)
                }
                when {
                    collVideosLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = C.t1, modifier = Modifier.size(26.dp))
                    }
                    collVideos.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("暂无视频", color = C.t2, fontSize = 13.sp)
                    }
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(cols),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        gridItems(collVideos, key = { it.id }) { v ->
                            SpaceVideoCard(v) { onPlay(v, collVideos) }
                        }
                    }
                }
            }
            return@Column
        }

        val list = if (sub == 1) seasons else series
        when {
            collLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = C.t1, modifier = Modifier.size(26.dp))
            }
            list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(collError.ifBlank { if (sub == 1) "暂无合集" else "暂无系列" },
                    color = C.t2, fontSize = 13.sp)
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(cols),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                gridItems(list, key = { it.id }) { c ->
                    Column(Modifier.clickable { onOpenColl(c) }) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(8.dp)).background(C.line)) {
                            if (c.cover.isNotEmpty()) {
                                AsyncImage(model = c.cover, contentDescription = c.title,
                                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                            Text("${c.total}个视频", color = Color.White, fontSize = 10.sp,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                                    .background(Color(0xCC000000), RoundedCornerShape(3.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(c.title, color = C.t1, fontSize = 13.sp, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun SpaceVideoCard(v: Video, onClick: () -> Unit) {
    Column(Modifier.clickable { onClick() }) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(8.dp)).background(C.line)) {
            if (v.pic.isNotEmpty()) {
                AsyncImage(model = v.pic, contentDescription = v.title,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (v.durationSec > 0) {
                Text(fmtDur(v.durationSec), color = Color.White, fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(v.title, color = C.t1, fontSize = 13.sp, maxLines = 2,
            overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(
            listOfNotNull(
                fmtRelTime(v.pubdate).ifBlank { null },
                if (v.playCount > 0) "${fmtCount(v.playCount)}播放" else null
            ).joinToString(" · "),
            color = C.t2, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun DynamicsTab(
    items: List<SpaceDyn>,
    loading: Boolean,
    error: String,
    hasMore: Boolean,
    onLoadMore: () -> Unit
) {
    when {
        loading && items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = C.t1, modifier = Modifier.size(26.dp))
        }
        items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error.ifBlank { "暂无动态" }, color = C.t2, fontSize = 13.sp)
        }
        else -> LazyColumn(Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listItems(items, key = { it.id }) { d ->
                Card(colors = CardDefaults.cardColors(containerColor = C.card)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(fmtRelTime(d.pubTs), color = C.t2, fontSize = 11.sp)
                        if (d.text.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(d.text, color = C.t1, fontSize = 13.sp, maxLines = 8,
                                overflow = TextOverflow.Ellipsis)
                        }
                        if (d.images.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                d.images.take(3).forEach { img ->
                                    AsyncImage(model = img, contentDescription = "动态图片",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(96.dp)
                                            .clip(RoundedCornerShape(6.dp)).background(C.line))
                                }
                            }
                        }
                        if (d.bvid.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (d.cover.isNotEmpty()) {
                                    AsyncImage(model = d.cover, contentDescription = d.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.width(92.dp).height(52.dp)
                                            .clip(RoundedCornerShape(6.dp)).background(C.line))
                                    Spacer(Modifier.width(8.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(d.title, color = C.t1, fontSize = 12.sp, maxLines = 2,
                                        overflow = TextOverflow.Ellipsis)
                                    if (d.duration.isNotBlank())
                                        Text(d.duration, color = C.t2, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
            item {
                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                    if (loading) CircularProgressIndicator(color = C.t1, modifier = Modifier.size(20.dp))
                    else if (hasMore) TextButton(onClick = onLoadMore) {
                        Text("加载更多", color = C.t2, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoritesTab(
    folders: List<SpaceFavFolder>,
    error: String,
    loading: Boolean,
    openFolderItem: SpaceFavFolder?,
    videos: List<Video>,
    loadingVideos: Boolean,
    cols: Int,
    onPlay: (Video, List<Video>) -> Unit,
    onOpen: (SpaceFavFolder) -> Unit,
    onClose: () -> Unit
) {
    if (openFolderItem != null) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("← 收藏夹", color = C.t1, fontSize = 13.sp) }
                Text(openFolderItem.title, color = C.t1, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            if (loadingVideos) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = C.t1, modifier = Modifier.size(26.dp))
                }
            } else if (videos.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("收藏夹为空", color = C.t2, fontSize = 13.sp)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(cols),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    gridItems(videos, key = { it.id }) { v ->
                        SpaceVideoCard(v) { onPlay(v, videos) }
                    }
                }
            }
        }
        return
    }
    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = C.t1, modifier = Modifier.size(26.dp))
        }
        folders.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error.ifBlank { "没有公开的收藏夹" }, color = C.t2, fontSize = 13.sp)
        }
        else -> LazyColumn(Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listItems(folders, key = { it.id }) { f ->
                Card(colors = CardDefaults.cardColors(containerColor = C.card),
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(f) }) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(f.title, color = C.t1, fontSize = 14.sp, modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${f.count} 个视频", color = C.t2, fontSize = 12.sp)
                        Spacer(Modifier.width(6.dp))
                        Text("›", color = C.t2, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

// ---------------- 解析 ----------------

private fun parseSpaceVideos(j: JSONObject?): Pair<List<Video>, Int> {
    if (j == null) return emptyList<Video>() to 0
    val data = j.optJSONObject("data") ?: return emptyList<Video>() to 0
    val list = data.optJSONObject("list")?.optJSONArray("vlist")
        ?: data.optJSONArray("archives")
        ?: data.optJSONArray("vlist")
        ?: return emptyList<Video>() to 0
    val total = data.optJSONObject("page")?.optInt("count", 0)
        ?: data.optInt("count", 0)
    val out = ArrayList<Video>(list.length())
    for (i in 0 until list.length()) {
        val o = list.optJSONObject(i) ?: continue
        val bvid = o.optString("bvid", "")
        if (bvid.isEmpty()) continue
        val aid = o.optLong("aid", bvid.hashCode().toLong())
        val title = o.optString("title", "")
            .replace("<em class=\"keyword\">", "").replace("</em>", "")
        val dur = if (o.has("duration")) o.optInt("duration", 0)
        else o.optString("length", "").split(":").fold(0) { acc, s ->
            acc * 60 + (s.toIntOrNull() ?: 0)
        }
        out.add(Video(
            id = aid, bvid = bvid, upId = "",
            title = title, durationSec = dur,
            pages = o.optInt("videos", 1),
            pic = normalizeUrl(o.optString("pic", o.optString("cover", ""))),
            playCount = o.optJSONObject("stat")?.optLong("view", 0L) ?: o.optLong("play", 0L),
            pubdate = o.optLong("pubdate", 0L)
        ))
    }
    return out to total
}

private fun parseDynamic(o: JSONObject?): SpaceDyn? {
    if (o == null) return null
    val id = o.optString("id_str", o.optString("id", ""))
    val modules = o.optJSONObject("modules") ?: return null
    val author = modules.optJSONObject("module_author")
    val dyn = modules.optJSONObject("module_dynamic")
    val desc = dyn?.optJSONObject("desc")?.optString("text", "") ?: ""
    val major = dyn?.optJSONObject("major")
    val type = major?.optString("type", "") ?: ""
    val images = ArrayList<String>()
    var bvid = ""; var title = ""; var cover = ""; var dur = ""
    var opusText = ""
    when (type) {
        "MAJOR_TYPE_DRAW" -> {
            val arr = major?.optJSONObject("draw")?.optJSONArray("items")
            if (arr != null) for (i in 0 until minOf(arr.length(), 9)) {
                val src = arr.optJSONObject(i)?.optString("src", "") ?: ""
                if (src.isNotEmpty()) images.add(normalizeUrl(src))
            }
        }
        "MAJOR_TYPE_ARCHIVE" -> {
            val a = major?.optJSONObject("archive")
            bvid = a?.optString("bvid", "") ?: ""
            title = a?.optString("title", "") ?: ""
            cover = normalizeUrl(a?.optString("cover", "") ?: "")
            dur = a?.optString("duration_text", "") ?: ""
        }
        "MAJOR_TYPE_OPUS" -> {
            val opus = major?.optJSONObject("opus")
            // 图文动态的正文在 opus.summary.text(不是 title),desc 通常为 null
            opusText = opus?.optJSONObject("summary")?.optString("text", "") ?: ""
            val pics = opus?.optJSONArray("pics")
            if (pics != null) for (i in 0 until minOf(pics.length(), 9)) {
                val src = pics.optJSONObject(i)?.optString("url", "") ?: ""
                if (src.isNotEmpty()) images.add(normalizeUrl(src))
            }
            if (title.isEmpty()) title = opus?.optString("title", "") ?: ""
        }
    }
    val text = desc.ifBlank { opusText.ifBlank { title } }
    if (text.isBlank() && images.isEmpty() && bvid.isBlank()) return null
    return SpaceDyn(
        id = id.ifBlank { "${author?.optLong("pub_ts", 0L)}-${text.hashCode()}" },
        pubTs = author?.optLong("pub_ts", 0L) ?: 0L,
        text = text,
        images = images,
        bvid = bvid,
        title = title,
        cover = cover,
        duration = dur
    )
}

private fun parseCollections(arr: org.json.JSONArray?, season: Boolean): List<SpaceCollection> {
    if (arr == null || arr.length() == 0) return emptyList()
    val out = ArrayList<SpaceCollection>(arr.length())
    for (i in 0 until arr.length()) {
        val meta = arr.optJSONObject(i)?.optJSONObject("meta") ?: continue
        val id = if (season) meta.optLong("season_id", 0L) else meta.optLong("series_id", 0L)
        if (id <= 0) continue
        out.add(SpaceCollection(
            id = id,
            title = meta.optString("name", ""),
            cover = normalizeUrl(meta.optString("cover", "")),
            total = meta.optInt("total", 0),
            season = season
        ))
    }
    return out
}

/** 合集/系列详情里的 archives(字段与视频列表不同:封面叫 pic,播放量在 stat.view)。 */
private fun parseArchives(j: JSONObject?): List<Video> {
    val arr = j?.optJSONObject("data")?.optJSONArray("archives") ?: return emptyList()
    val out = ArrayList<Video>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val bvid = o.optString("bvid", "")
        if (bvid.isEmpty()) continue
        out.add(Video(
            id = o.optLong("aid", bvid.hashCode().toLong()),
            bvid = bvid, upId = "",
            title = o.optString("title", ""),
            durationSec = o.optInt("duration", 0),
            pic = normalizeUrl(o.optString("pic", o.optString("cover", ""))),
            playCount = o.optJSONObject("stat")?.optLong("view", 0L) ?: 0L,
            pubdate = o.optLong("pubdate", 0L)
        ))
    }
    return out
}

private fun parseFavVideos(j: JSONObject?): List<Video> {
    val data = j?.optJSONObject("data") ?: return emptyList()
    val medias = data.optJSONArray("medias") ?: return emptyList()
    val out = ArrayList<Video>(medias.length())
    for (i in 0 until medias.length()) {
        val o = medias.optJSONObject(i) ?: continue
        val bvid = o.optString("bvid", "")
        if (bvid.isEmpty()) continue
        out.add(Video(
            id = o.optLong("id", bvid.hashCode().toLong()),
            bvid = bvid,
            upId = o.optJSONObject("upper")?.optString("mid", "") ?: "",
            title = o.optString("title", ""),
            durationSec = o.optInt("duration", 0),
            pic = normalizeUrl(o.optString("cover", "")),
            playCount = o.optJSONObject("cnt_info")?.optLong("play", 0L) ?: 0L,
            pubdate = o.optLong("pubtime", 0L)
        ))
    }
    return out
}

private fun normalizeUrl(u: String): String {
    var s = u.trim()
    if (s.startsWith("//")) s = "https:" + s
    if (s.startsWith("http://")) s = "https://" + s.substringAfter("http://")
    return s
}

private fun fmtCount(n: Long): String = when {
    n >= 100_000_000 -> String.format(Locale.US, "%.1f亿", n / 100_000_000.0)
    n >= 10_000 -> String.format(Locale.US, "%.1f万", n / 10_000.0)
    else -> n.toString()
}

private fun fmtDur(sec: Int): String {
    val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

private fun fmtRelTime(sec: Long): String {
    if (sec <= 0) return ""
    val diff = System.currentTimeMillis() / 1000 - sec
    return when {
        diff < 60 -> "刚刚"
        diff < 3600 -> "${diff / 60}分钟前"
        diff < 86_400 -> "${diff / 3600}小时前"
        diff < 7 * 86_400 -> "${diff / 86_400}天前"
        else -> try {
            // 超过 7 天显示具体发布日期(与 B 站一致:今年 10月31日,往年 2025年10月31日)
            val d = Date(sec * 1000)
            val nowYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            val dYear = java.util.Calendar.getInstance().apply { time = d }.get(java.util.Calendar.YEAR)
            SimpleDateFormat(if (dYear == nowYear) "M月d日" else "yyyy年M月d日", Locale.getDefault()).format(d)
        } catch (_: Exception) { "" }
    }
}
