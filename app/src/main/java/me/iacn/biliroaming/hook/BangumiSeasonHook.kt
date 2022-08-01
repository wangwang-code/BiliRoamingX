package me.iacn.biliroaming.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import kotlinx.coroutines.*
import me.iacn.biliroaming.*
import me.iacn.biliroaming.API.*
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.Constant.TYPE_EPISODE_ID
import me.iacn.biliroaming.Constant.TYPE_SEASON_ID
import me.iacn.biliroaming.network.BiliRoamingApi
import me.iacn.biliroaming.network.BiliRoamingApi.getAreaSearchBangumi
import me.iacn.biliroaming.network.BiliRoamingApi.getContent
import me.iacn.biliroaming.network.BiliRoamingApi.getSeason
import me.iacn.biliroaming.network.BiliRoamingApi.getSpace
import me.iacn.biliroaming.utils.*
import org.json.JSONObject
import java.io.InputStream
import java.lang.reflect.Method
import java.net.URL
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.Array
import kotlin.Boolean
import kotlin.Int
import java.lang.reflect.Array as RArray

/**
 * Created by iAcn on 2019/3/27
 * Email i@iacn.me
 */
class BangumiSeasonHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    companion object {
        val lastSeasonInfo: MutableMap<String, String?> = HashMap()

        private val jsonNonStrict = lazy {
            instance.kotlinJsonClass?.getStaticObjectField("Companion")?.callMethod("getNonstrict")
        }
        const val FAIL_CODE = -404

        data class Area(val area: String, val text: String, val type: String, val typeStr: String)

        private val AREA_TYPES = mapOf(
            931 to Area("cn", "陆(影)", "8", "movie"),
            364364 to Area("hk", "港(影)", "8", "movie"),
            889464 to Area("tw", "台(影)", "8", "movie"),
            114 to Area("th", "泰", "7", "bangumi"),
            514 to Area("cn", "陆", "7", "bangumi"),
            1919 to Area("hk", "港", "7", "bangumi"),
            810 to Area("tw", "台", "7", "bangumi")
        )
    }

    private val isSerializable by lazy {
        "com.bilibili.bangumi.data.page.detail.entity.BangumiUniformSeason\$\$serializer".findClassOrNull(
            mClassLoader
        ) != null
    }

    private val isGson by lazy {
        instance.bangumiUniformSeasonClass?.annotations?.any {
            it.annotationClass.java.name.startsWith("gsonannotator")
        } ?: false && instance.gsonFromJson() != null && instance.gsonToJson() != null
    }

    private val gson by lazy {
        instance.gson()?.let { instance.gsonConverterClass?.getStaticObjectField(it) }
    }

    private val serializerFeatures = lazy {
        val serializerFeatureClass =
            "com.alibaba.fastjson.serializer.SerializerFeature".findClassOrNull(mClassLoader)
                ?: return@lazy null
        val keyAsString = serializerFeatureClass.getStaticObjectField("WriteNonStringKeyAsString")
        val noDefault = serializerFeatureClass.getStaticObjectField("NotWriteDefaultValue")
        val serializerFeatures = RArray.newInstance(serializerFeatureClass, 2)
        RArray.set(serializerFeatures, 0, keyAsString)
        RArray.set(serializerFeatures, 1, noDefault)
        serializerFeatures
    }

    private fun Any.toJson() = when {
        isSerializable -> jsonNonStrict.value?.callMethodAs<String>(
            "stringify",
            javaClass.getStaticObjectField("Companion")?.callMethod("serializer"),
            this
        ).toJSONObject()

        isGson -> gson?.callMethodAs<String>(instance.gsonToJson(), this)?.toJSONObject()
        else -> instance.fastJsonClass?.callStaticMethodAs<String>(
            "toJSONString",
            this,
            serializerFeatures.value
        ).toJSONObject()
    }

    private fun Class<*>.fromJson(json: String) = when {
        isSerializable -> jsonNonStrict.value?.callMethod(
            "parse",
            getStaticObjectField("Companion")?.callMethod("serializer"),
            json
        )

        isGson -> gson?.callMethod(instance.gsonFromJson(), json, this)
        else -> instance.fastJsonClass?.callStaticMethod(instance.fastJsonParse(), json, this)
    }

    private fun Class<*>.fromJson(json: JSONObject) = fromJson(json.toString())

    private fun updateSeasonInfo(args: Array<*>, paramMap: Map<String, String>) {
        lastSeasonInfo.clear()
        val seasonMode = when {
            args[1] is Int -> args[1] as Int
            args[3] != "0" && paramMap.containsKey("ep_id") -> TYPE_EPISODE_ID
            args[2] != "0" && paramMap.containsKey("season_id") -> TYPE_SEASON_ID
            else -> -1
        }
        when (seasonMode) {
            TYPE_SEASON_ID -> lastSeasonInfo["season_id"] = paramMap["season_id"]
            TYPE_EPISODE_ID -> lastSeasonInfo["ep_id"] = paramMap["ep_id"]
            else -> return
        }
    }

    private val searchAllResultClass by Weak {
        "com.bilibili.search.api.SearchResultAll".findClassOrNull(
            mClassLoader
        )
    }
    private val searchAllResultNavInfoClass by Weak {
        "com.bilibili.search.api.SearchResultAll\$NavInfo".findClassOrNull(
            mClassLoader
        )
    }
    private val bangumiSearchPageClass by Weak {
        "com.bilibili.bangumi.data.page.search.BangumiSearchPage".findClassOrNull(
            mClassLoader
        ) ?: "com.bilibili.search.result.bangumi.ogv.BangumiSearchPage".findClassOrNull(
            mClassLoader
        )
    }
    private val biliSearchOgvResultClass by Weak {
        "com.bilibili.search.ogv.BiliSearchOgvResult".findClassOrNull(
            mClassLoader
        )
    }
    private val baseSearchItemClass by Weak {
        "com.bilibili.search.api.BaseSearchItem".findClassOrNull(
            mClassLoader
        )
    }

    private val navClass by Weak { "com.bapis.bilibili.polymer.app.search.v1.Nav" from mClassLoader }
    private val searchByTypeRespClass by Weak { "com.bapis.bilibili.polymer.app.search.v1.SearchByTypeResponse" from mClassLoader }

    @SuppressLint("SetTextI18n")
    override fun startHook() {
        if (!sPrefs.getBoolean("main_func", false)) return
        Log.d("startHook: BangumiSeason")
        instance.seasonParamsMapClass?.hookAfterAllConstructors { param ->
            @Suppress("UNCHECKED_CAST")
            val paramMap = param.thisObject as Map<String, String>
            updateSeasonInfo(param.args, paramMap)
        }

        instance.seasonParamsClass?.hookAfterAllConstructors { param ->
            val paramMap =
                param.thisObject.callMethodAs<Map<String, String>>(instance.paramsToMap())
            updateSeasonInfo(param.args, paramMap)
        }

        val invalidTipsHook: Hooker = { param ->
            val view = param.args[0]?.let {
                if (it is View) it else it.callMethod("getView") as View?
            }
            if (lastSeasonInfo["allow_comment"] == "0" &&
                !sPrefs.getBoolean("force_th_comment", false)
            ) {
                view?.findViewById<TextView>(getId("info"))?.text =
                    "由于泰区番剧评论会串到其他正常视频中，\n因而禁用泰区评论，还望理解。"
                view?.findViewById<ImageView>(getId("forbid_icon"))?.run {
                    MainScope().launch {
                        withContext(Dispatchers.IO) {
                            URL("https://i0.hdslb.com/bfs/album/08d5ce2fef8da8adf91024db4a69919b8d02fd5c.png")
                                .openStream().let { BitmapFactory.decodeStream(it) }
                        }.let { setImageBitmap(it) }
                    }
                }
            }
        }
        instance.commentInvalidFragmentClass?.run {
            hookAfterMethod(
                "onViewCreated",
                View::class.java,
                Bundle::class.java,
                hooker = invalidTipsHook
            )
            instance.setInvalidTips()?.let {
                hookAfterMethod(it, this, "kotlin.Pair", hooker = invalidTipsHook)
            }
        }

        "com.bilibili.bangumi.ui.page.detail.BangumiDetailActivityV3".hookAfterMethod(
            mClassLoader, "onCreate", Bundle::class.java
        ) {
            setErrorMessage(it.thisObject as Activity)
        }
        "com.bilibili.bangumi.ui.page.detail.BangumiDetailActivityV3".hookAfterMethod(
            mClassLoader, "onConfigurationChanged", Configuration::class.java
        ) {
            setErrorMessage(it.thisObject as Activity)
        }

        if (isBuiltIn && is64 && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.e("Not support")
            Log.toast("Android O以下系统不支持64位Xpatch版，请使用32位版")
        } else {
            instance.retrofitResponseClass?.hookBeforeAllConstructors { param ->
                val url = getRetrofitUrl(param.args[0])
                val body = param.args[1] ?: return@hookBeforeAllConstructors
                if (url?.startsWith("https://api.bilibili.com/pgc/view/v2/app/season") == true &&
                    body.javaClass == instance.okioWrapperClass
                ) {
                    val okioBuffer = body.getObjectField(instance.okio())
                    val json =
                        okioBuffer?.callMethodAs<InputStream?>(instance.okioInputStream())?.use {
                            it.readBytes().toString(Charsets.UTF_8).toJSONObject()
                        }?.apply {
                            put(
                                "data",
                                fixBangumi(optJSONObject("data"), optInt("code", FAIL_CODE), url)
                            )
                            put("code", 0)
                        }
                    val newStream = json?.toString()?.byteInputStream()
                    body.setObjectField(
                        instance.okio(),
                        okioBuffer?.javaClass?.newInstance()?.apply {
                            callMethod(instance.okioReadFrom(), newStream)
                        })
                    body.setLongField(instance.okioLength(), newStream?.available()?.toLong() ?: 0L)
                }
                // Filter non-bangumi responses
                // If it isn't bangumi, the type variable will not exist in this map
                if (instance.bangumiApiResponseClass?.isInstance(body) == true ||
                    // for new blue 6.3.7
                    instance.rxGeneralResponseClass?.isInstance(body) == true
                ) {
                    fixBangumi(body, url)
                }
                if (url != null && url.startsWith("https://appintl.biliapi.net/intl/gateway/app/search/type")
                ) {
                    val area = Uri.parse(url).getQueryParameter("type")?.toInt()
                    if (!AREA_TYPES.containsKey(area)) {
                        fixPlaySearchType(body, url)
                    }
                }
                if (instance.generalResponseClass?.isInstance(body) == true ||
                    instance.rxGeneralResponseClass?.isInstance(body) == true
                ) {
                    val dataField =
                        if (instance.generalResponseClass?.isInstance(body) == true) "data" else instance.responseDataField()
                    val data = body.getObjectField(dataField)
                    if (data?.javaClass == searchAllResultClass) {
                        addAreaTags(data)
                    }
                    url ?: return@hookBeforeAllConstructors
                    if (url.startsWith("https://app.bilibili.com/x/v2/search/type") || url.startsWith(
                            "https://appintl.biliapi.net/intl/gateway/app/search/type"
                        ) || url.startsWith("https://app.bilibili.com/x/intl/search/type")
                    ) {
                        val area = Uri.parse(url).getQueryParameter("type")?.toInt()
                        if (AREA_TYPES.containsKey(area)) {
                            if (data?.javaClass == bangumiSearchPageClass) {
                                body.setObjectField(dataField, AREA_TYPES[area]?.let {
                                    retrieveAreaSearch(data, url, it.area, it.type)
                                })
                            } else if (data?.javaClass == biliSearchOgvResultClass) {
                                body.setObjectField(dataField, AREA_TYPES[area]?.let {
                                    retrieveAreaSearchV2(data, url, it.area, it.type)
                                })
                            }
                        }
                    } else if (url.startsWith("https://app.bilibili.com/x/v2/view?") ||
                        url.startsWith("https://app.bilibili.com/x/intl/view?") ||
                        url.startsWith("https://appintl.biliapi.net/intl/gateway/app/view?") &&
                        body.getIntField("code") == FAIL_CODE
                    ) {
                        fixView(data, url)?.let {
                            body.setObjectField(dataField, it)
                            body.setIntField("code", 0)
                        }
                    } else if (url.startsWith("https://app.bilibili.com/x/v2/space?")) {
                        val code = body.getIntFieldOrNull("code")
                        val mid = Uri.parse(url).getQueryParameter("vmid")?.toLongOrNull()
                        if (code != 0 && sPrefs.getBoolean("fix_space", false)) {
                            fixSpace(mid)?.let {
                                body.setObjectField(dataField, it)
                                body.setIntField("code", 0)
                            }
                        }
                    }
                }
            }
        }

        instance.viewMossClass?.hookAfterMethod("view", instance.viewReqClass) { param ->
            param.result?.let { return@hookAfterMethod }
            val serializedRequest = param.args[0].callMethodAs<ByteArray>("toByteArray")
            val req = ViewReq.parseFrom(serializedRequest)
            val reply = fixViewProto(req)
            val serializedReply = reply?.toByteArray() ?: return@hookAfterMethod
            param.result = (param.method as Method).returnType
                .callStaticMethod("parseFrom", serializedReply)
        }

        val urlHook: Hooker = fun(param) {
            val redirectUrl = param.thisObject.getObjectFieldAs<String?>("redirectUrl")
            if (redirectUrl.isNullOrEmpty()) return
            param.result = param.thisObject.callMethod("getUrl", redirectUrl)
        }
        "com.bilibili.bplus.followingcard.api.entity.cardBean.VideoCard".from(mClassLoader)?.run {
            hookAfterMethod("getJumpUrl", hooker = urlHook)
            hookAfterMethod("getCommentJumpUrl", hooker = urlHook)
        }

        if (sPrefs.getBoolean("hidden", false) &&
            (sPrefs.getBoolean("search_area_bangumi", false)
                    || sPrefs.getBoolean("search_area_movie", false))
        ) {
            val searchResultFragment =
                "com.bilibili.bangumi.ui.page.search.BangumiSearchResultFragment".from(mClassLoader)
                    ?: "com.bilibili.search.result.bangumi.ogv.BangumiSearchResultFragment"
                        .from(mClassLoader) ?: "com.bilibili.search.ogv.OgvSearchResultFragment"
                        .from(mClassLoader)
            searchResultFragment?.run {
                val intTypeFields = declaredFields.filter {
                    it.type == Int::class.javaPrimitiveType
                }.onEach { it.isAccessible = true }
                hookBeforeMethod(
                    "setUserVisibleCompat",
                    Boolean::class.javaPrimitiveType
                ) { param ->
                    param.thisObject.callMethodAs<Bundle>("getArguments").run {
                        val from = getString("from")
                        for (area in AREA_TYPES) {
                            if (from == area.value.area) {
                                intTypeFields.forEach {
                                    if (it.get(param.thisObject) == area.value.type.toInt())
                                        it.set(param.thisObject, area.key)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (sPrefs.getBoolean("hidden", false) &&
            (sPrefs.getBoolean("search_area_bangumi", false)
                    || sPrefs.getBoolean("search_area_movie", false))
        ) {
            val mossResponseHandlerClass = instance.mossResponseHandlerClass ?: return
            val searchMossClass =
                "com.bapis.bilibili.polymer.app.search.v1.SearchMoss".from(mClassLoader) ?: return
            searchMossClass.hookBeforeMethod(
                "searchAll",
                "com.bapis.bilibili.polymer.app.search.v1.SearchAllRequest",
                mossResponseHandlerClass
            ) { param ->
                param.args[1] = param.args[1].mossResponseHandlerProxy {
                    addAreaTagsV2(it)
                }
            }
            searchMossClass.hookBeforeMethod(
                "searchByType",
                "com.bapis.bilibili.polymer.app.search.v1.SearchByTypeRequest",
                mossResponseHandlerClass
            ) { param ->
                val searchByTypeRespClass = searchByTypeRespClass ?: return@hookBeforeMethod
                val key =
                    param.args[0].callMethodOrNullAs<Int>("getType") ?: return@hookBeforeMethod
                val areaType = AREA_TYPES[key] ?: return@hookBeforeMethod
                val request = SearchByTypeRequest.parseFrom(
                    param.args[0].callMethodAs<ByteArray>("toByteArray")
                )
                val type = areaType.type
                val area = areaType.area
                val handler = param.args[1]
                MainScope().launch(Dispatchers.IO) {
                    val result = retrieveAreaSearchV3(request, area, type)
                    if (result != null) {
                        val newRes = searchByTypeRespClass
                            .callStaticMethod("parseFrom", result.toByteArray())
                        handler.callMethod("onNext", newRes)
                        handler.callMethod("onCompleted")
                    } else {
                        handler.callMethod("onError", null)
                    }
                }
                param.result = null
            }
        }
    }

    private fun addAreaTagsV2(v: Any?) {
        v ?: return
        val navClass = navClass ?: return
        val navList = v.callMethodAs<List<Any>>("getNavList")
            .map { SearchNav.parseFrom(it.callMethodAs<ByteArray>("toByteArray")) }
            .toMutableList()
        val currentArea = runCatchingOrNull {
            XposedInit.country.get(5L, TimeUnit.SECONDS)
        }
        for (area in AREA_TYPES) {
            if (area.value.area == currentArea)
                continue
            if (!sPrefs.getString(area.value.area + "_server", null).isNullOrBlank() &&
                sPrefs.getBoolean("search_area_" + area.value.typeStr, false)
            ) {
                val nav = searchNav {
                    name = area.value.text
                    total = 0
                    pages = 0
                    type = area.key
                }
                navList.add(1, nav)
            }
        }
        v.callMethod("clearNav")
        val newNavList = navList.map {
            navClass.callStaticMethod("parseFrom", it.toByteArray())
        }
        v.callMethod("addAllNav", newNavList)
    }

    override fun lateInitHook() {
        if (sPrefs.getBoolean("hidden", false) &&
            (sPrefs.getBoolean("search_area_bangumi", false)
                    || sPrefs.getBoolean("search_area_movie", false))
        ) {
            val pageTypesClass = Class.forName(
                "com.bilibili.search.result.pages.BiliMainSearchResultPage\$PageTypes",
                true, mClassLoader
            )
            val pageArray = pageTypesClass.getStaticObjectFieldAs<Array<Any>>("\$VALUES")
            val extra = AREA_TYPES.mapNotNull { area ->
                sPrefs.getString(area.value.area + "_server", null)
                    .takeUnless { it.isNullOrBlank() }?.run {
                        pageTypesClass.new(
                            "PAGE_" + area.value.typeStr.uppercase(Locale.getDefault()), 4,
                            "bilibili://search-result/new-" + area.value.typeStr + "?from=" + area.value.area,
                            area.key, area.value.typeStr
                        )
                    }
            }
            val newPageArray = pageArray.copyOf(pageArray.size + extra.size)
            extra.forEachIndexed { index, any -> newPageArray[pageArray.size + index] = any }
            pageTypesClass.setStaticObjectField("\$VALUES", newPageArray)
        }
    }

    private fun retrieveAreaSearchV3(
        request: SearchByTypeRequest,
        area: String,
        type: String
    ): SearchByTypeResponse? {
        val pn = request.pagination.next.ifEmpty { "1" }
        val ps = request.pagination.pageSize
        val keyword = request.keyword
        val query = mapOf(
            "access_key" to (instance.accessKey ?: ""),
            "fnval" to request.playerArgs.fnval.toString(),
            "fnver" to request.playerArgs.fnver.toString(),
            "qn" to request.playerArgs.qn.toString(),
            "pn" to pn,
            "ps" to ps.toString(),
            "keyword" to keyword,
        )
        val jsonContent = getAreaSearchBangumi(query, area, type)?.toJSONObject()
            ?: return null
        checkErrorToast(jsonContent, true)
        val newData = jsonContent.optJSONObject("data") ?: return null

        fun ReasonStyleKt.Dsl.reconstructFrom(json: JSONObject) = json.run {
            text = optString("text")
            textColor = optString("text_color")
            textColorNight = optString("text_color_night")
            bgColor = optString("bg_color")
            bgColorNight = optString("bg_color_night")
            borderColor = optString("border_color")
            borderColorNight = optString("border_color_night")
            bgStyle = optInt("bg_style")
        }

        fun EpisodeKt.Dsl.reconstructFrom(json: JSONObject) = json.run {
            uri = optString("uri")
            param = optString("param")
            index = optString("index")
            for (badge in optJSONArray("badges").orEmpty())
                badges += reasonStyle { reconstructFrom(badge) }
            position = optInt("position")
        }

        fun EpisodeNewKt.Dsl.reconstructFrom(json: JSONObject) = json.run {
            title = optString("title")
            uri = optString("uri")
            param = optString("param")
            isNew = optInt("is_new")
            for (badge in optJSONArray("badges").orEmpty())
                badges += reasonStyle { reconstructFrom(badge) }
            this@reconstructFrom.type = optInt("type")
            position = optInt("position")
            cover = optString("cover")
            label = optString("label")
        }

        fun WatchButtonKt.Dsl.reconstructFrom(json: JSONObject) = json.run {
            title = optString("title")
            link = optString("link")
        }

        fun CheckMoreKt.Dsl.reconstructFrom(json: JSONObject) = json.run {
            content = optString("content")
            uri = optString("uri")
        }

        fun FollowButtonKt.Dsl.reconstructFrom(json: JSONObject) = json.run {
            icon = optString("icon")
            optJSONObject("texts")?.let { o ->
                o.keys().asSequence().associateWith { o.opt(it)?.toString() ?: "" }
            }?.let { texts.putAll(it) }
            statusReport = optString("status_report")
        }

        fun SearchBangumiCardKt.Dsl.reconstructFrom(json: JSONObject) = json.run {
            title = optString("title")
            cover = optString("cover")
            mediaType = optInt("media_type")
            playState = optInt("play_state")
            this@reconstructFrom.area = optString("area")
            style = optString("style")
            styles = optString("styles")
            cv = optString("cv")
            rating = optDouble("rating")
            vote = optInt("vote")
            target = optString("target")
            staff = optString("staff")
            prompt = optString("prompt")
            ptime = optLong("ptime")
            seasonTypeName = optString("season_type_name")
            for (episode in optJSONArray("episodes").orEmpty())
                episodes += episode { reconstructFrom(episode) }
            isSelection = optInt("is_selection")
            isAtten = optInt("is_atten")
            label = optString("label")
            seasonId = optLong("season_id")
            outName = optString("out_name")
            outIcon = optString("out_icon")
            outUrl = optString("out_url")
            for (badge in optJSONArray("badges").orEmpty())
                badges += reasonStyle { reconstructFrom(badge) }
            isOut = optInt("is_out")
            for (episodeNew in optJSONArray("episodes_new").orEmpty())
                episodesNew += episodeNew { reconstructFrom(episodeNew) }
            optJSONObject("watch_button")?.let {
                watchButton = watchButton { reconstructFrom(it) }
            }
            selectionStyle = optString("selection_style")
            optJSONObject("check_more")?.let {
                checkMore = checkMore { reconstructFrom(it) }
            }
            optJSONObject("follow_button")?.let {
                followButton = followButton { reconstructFrom(it) }
            }
            optJSONObject("style_label")?.let {
                styleLabel = reasonStyle { reconstructFrom(it) }
            }
            for (badgeV2 in optJSONArray("badges_v2").orEmpty())
                badgesV2 += reasonStyle { reconstructFrom(badgeV2) }
            stylesV2 = optString("styles_v2")
        }

        fun SearchItemKt.Dsl.reconstructFrom(json: JSONObject) = json.run {
            uri = optString("uri")
            param = optString("param")
            goto = optString("goto")
            linkType = optString("link_type")
            position = optInt("position")
            trackId = optString("track_id")
            bangumi = searchBangumiCard { reconstructFrom(json) }
        }

        val pages = newData.optInt("pages")
        var page = pn.toIntOrNull() ?: 1
        val response = searchByTypeResponse {
            this.pages = pages
            this.keyword = keyword
            for (json in newData.optJSONArray("items").orEmpty()) {
                if (json.optInt("Offset", -1) != -1)
                    json.remove("follow_button")
                items += searchItem { reconstructFrom(json) }
            }
            if (page < pages)
                pagination = paginationReply { next = (++page).toString() }
        }
        return response
    }

    private fun fixSpace(mid: Long?): Any? {
        mid ?: return null
        instance.biliSpaceClass ?: return null
        return getSpace(mid)?.let {
            instance.fastJsonClass?.callStaticMethodOrNull(
                instance.fastJsonParse(),
                it,
                instance.biliSpaceClass
            )
        }
    }

    private fun retrieveAreaSearchV2(data: Any?, url: String, area: String, type: String): Any? {
        data ?: return null
        if (sPrefs.getBoolean("hidden", false) && (sPrefs.getBoolean(
                "search_area_bangumi", false
            ) || sPrefs.getBoolean("search_area_movie", false))
        ) {
            val query = Uri.parse(url).run {
                queryParameterNames.associateWith { getQueryParameter(it) ?: "" }
            }
            val content = getAreaSearchBangumi(query, area, type) ?: return data
            val jsonContent = content.toJSONObject()
            checkErrorToast(jsonContent, true)
            val newData = jsonContent.optJSONObject("data") ?: return data

            val newItems = mutableListOf<Any>()
            for (item in newData.optJSONArray("items").orEmpty()) {
                // 去除追番按钮
                if (item.optInt("Offset", -1) != -1) {
                    item.remove("follow_button")
                }
                val goto = baseSearchItemClass?.getStaticObjectFieldAs<Map<String, Any>>("sMap")
                    ?.get(item.optString("goto"))
                instance.fastJsonClass?.callStaticMethod(
                    instance.fastJsonParse(), item.toString(), goto?.callMethod("getImpl")
                )?.let {
                    it.setIntField("viewType", goto?.callMethodAs<String>("getLayout").hashCode())
                    newItems.add(it)
                }
            }
            return data.javaClass.new()
                .setObjectField("items", newItems)
                .setIntField("totalPages", newData.optInt("pages"))
        } else {
            return data
        }
    }

    private fun retrieveAreaSearch(data: Any?, url: String, area: String, type: String): Any? {
        data ?: return null
        if (sPrefs.getBoolean("hidden", false) && (sPrefs.getBoolean(
                "search_area_bangumi", false
            ) || sPrefs.getBoolean("search_area_movie", false))
        ) {
            val query = Uri.parse(url).run {
                queryParameterNames.associateWith { getQueryParameter(it) ?: "" }
            }
            val content = getAreaSearchBangumi(query, area, type) ?: return data
            val jsonContent = content.toJSONObject()
            checkErrorToast(jsonContent, true)
            val newData = jsonContent.optJSONObject("data") ?: return data

            // 去除追番按钮
            for (item in newData.optJSONArray("items").orEmpty()) {
                if (item.optInt("Offset", -1) != -1) {
                    item.remove("follow_button")
                }
            }

            return instance.fastJsonClass?.callStaticMethod(
                instance.fastJsonParse(),
                newData.toString(),
                data.javaClass
            ) ?: data
        } else {
            return data
        }
    }

    private fun addAreaTags(body: Any?) {
        body ?: return
        if (sPrefs.getBoolean("hidden", false) &&
            (sPrefs.getBoolean(
                "search_area_bangumi",
                false
            ) || sPrefs.getBoolean("search_area_movie", false))
        ) {
            val currentArea = runCatchingOrNull {
                XposedInit.country.get(5L, TimeUnit.SECONDS)
            }
            for (area in AREA_TYPES) {
                if (area.value.area == currentArea)
                    continue
                if (!sPrefs.getString(area.value.area + "_server", null).isNullOrBlank() &&
                    sPrefs.getBoolean("search_area_" + area.value.typeStr, false)
                ) {
                    searchAllResultNavInfoClass?.new()?.run {
                        setObjectField("name", area.value.text)
                        setIntField("pages", 0)
                        setIntField("total", 0)
                        setIntField("type", area.key)
                    }?.also {
                        body.getObjectFieldAs<MutableList<Any>>("nav").add(1, it)
                    }
                }
            }
        }
    }

    private fun fixPlaySearchType(body: Any, url: String) {
        val dataField =
            if (instance.generalResponseClass?.isInstance(body) == true) "data" else instance.responseDataField()
        val resultClass = body.getObjectField(dataField)?.javaClass ?: return
        if (!url.contains("type=7") && !url.contains("type=8")) return
        val newUrl = url.replace("appintl.biliapi.net/intl/gateway/app/", "app.bilibili.com/x/v2/")
        val content = getContent(newUrl)?.toJSONObject()?.optJSONObject("data") ?: return
        val newResult = resultClass.fromJson(content) ?: return
        body.setObjectField(dataField, newResult)
    }

    private fun fixBangumi(jsonResult: JSONObject?, code: Int, url: String?) =
        if (isBangumiWithWatchPermission(jsonResult, code)) {
            BangumiPlayUrlHook.qnApplied.set(false)
            lastSeasonInfo.clear()
            lastSeasonInfo["title"] = jsonResult?.optString("title")
            for (module in jsonResult?.optJSONArray("modules").orEmpty()) {
                val data = module.optJSONObject("data")
                val moduleEpisodes = data?.optJSONArray("episodes")
                for (episode in moduleEpisodes.orEmpty()) {
                    val id = episode.optString("id")
                    lastSeasonInfo["ep_title_$id"] = episode.optString("ep_index")
                }
            }
            jsonResult?.also { allowDownload(it); fixEpisodesStatus(it) }
        } else {
            BangumiPlayUrlHook.qnApplied.set(false)
            url?.let { Uri.parse(it) }?.run {
                getQueryParameter("ep_id")?.let {
                    lastSeasonInfo.clear()
                    lastSeasonInfo["ep_id"] = it
                }
                getQueryParameter("season_id")?.let {
                    lastSeasonInfo.clear()
                    lastSeasonInfo["season_id"] = it
                }
            }
            Log.toast("发现区域限制番剧，尝试解锁……")
            Log.d("Info: $lastSeasonInfo")
            val (newCode, newJsonResult) = getSeason(
                lastSeasonInfo,
                jsonResult == null
            )?.toJSONObject()?.let {
                it.optInt("code", FAIL_CODE) to it.optJSONObject("result")
            } ?: (FAIL_CODE to null)
            if (isBangumiWithWatchPermission(newJsonResult, newCode)) {
                lastSeasonInfo["title"] = newJsonResult?.optString("title")
                lastSeasonInfo["season_id"] = newJsonResult?.optString("season_id")
                lastSeasonInfo["watch_platform"] =
                    newJsonResult?.optJSONObject("rights")?.apply {
                        if (has("allow_comment") && getInt("allow_comment") == 0) {
                            remove("allow_comment")
                            put("area_limit", 1)
                            lastSeasonInfo["allow_comment"] = "0"
                        }
                    }?.optInt("watch_platform")?.toString()
                for (episode in newJsonResult?.optJSONArray("episodes").orEmpty()) {
                    if (episode.has("cid") && episode.has("id")) {
                        val cid = episode.optInt("cid").toString()
                        val epId = episode.optInt("id").toString()
                        lastSeasonInfo[cid] = epId
                        lastSeasonInfo["ep_ids"] = lastSeasonInfo["ep_ids"]?.let { "$it;$epId" }
                            ?: epId
                        lastSeasonInfo["ep_title_$epId"] = episode.optString("index")
                        episode.optJSONArray("subtitles")?.let {
                            if (it.length() > 0) {
                                lastSeasonInfo["area"] = "th"
                                lastSeasonInfo["sb$cid"] = it.toString()
                            }
                        }
                    }
                }
                allowDownload(newJsonResult, false)
                fixEpisodesStatus(newJsonResult)
            }
            jsonResult?.apply {
                // Replace only episodes and rights
                // Remain user information, such as follow status, watch progress, etc.
                put("rights", newJsonResult?.optJSONObject("rights"))
                put("episodes", newJsonResult?.optJSONArray("episodes"))
                remove("limit")
                put("modules", newJsonResult?.optJSONArray("modules"))
                put("section", newJsonResult?.optJSONArray("section"))
                put("prevueSection", newJsonResult?.optJSONArray("prevueSection"))
                remove("dialog")
            } ?: newJsonResult
        }

    private fun fixBangumi(body: Any, url: String?) {
        val fieldName = "_data"
        //if (isSerializable || isGson) instance.responseDataField().value else "result"
        val result = body.getObjectField(fieldName)
        val code = body.getIntField("code")
        if (instance.bangumiUniformSeasonClass?.isInstance(result) != true && code != FAIL_CODE) return
        if (url?.contains("cards?") == true) return
        val jsonResult = result?.toJson()
        // Filter normal bangumi and other responses
        fixBangumi(jsonResult, code, url)?.let {
            body.setIntField("code", 0)
                .setObjectField(fieldName, instance.bangumiUniformSeasonClass?.fromJson(it))
        } ?: run {
            lastSeasonInfo.clear()
        }
    }

    private fun fixViewProto(req: ViewReq): ViewReply? {
        val av = when {
            req.hasAid() -> req.aid.toString()
            req.hasBvid() -> bv2av(req.bvid).toString()
            else -> return null
        }
        val query = Uri.Builder().run {
            appendQueryParameter("id", av)
            appendQueryParameter("bvid", req.bvid.toString())
            appendQueryParameter("from", req.from.toString())
            appendQueryParameter("trackid", req.trackid.toString())
            appendQueryParameter("ad_extra", req.adExtra.toString())
            appendQueryParameter("qn", req.qn.toString())
            appendQueryParameter("fnver", req.fnver.toString())
            appendQueryParameter("fnval", req.fnval.toString())
            appendQueryParameter("force_host", req.forceHost.toString())
            appendQueryParameter("fourk", req.fourk.toString())
            appendQueryParameter("spmid", req.spmid.toString())
            appendQueryParameter("autoplay", req.autoplay.toString())
            build()
        }.query

        BiliRoamingApi.getPagelist(av) ?: return null

        Log.toast("发现区域限制视频，尝试解锁……")

        val content = BiliRoamingApi.getView(query)?.toJSONObject() ?: return null
        val result = content.optJSONObject("v2_app_api") ?: return null

        return viewReply {
            arc = arc {
                author = author {
                    result.optJSONObject("owner")?.run {
                        mid = optLong("mid")
                        face = optString("face")
                        name = optString("name")
                    }
                }
                dimension = dimension {
                    result.optJSONObject("dimension")?.run {
                        width = optLong("width")
                        height = optLong("height")
                        rotate = optLong("rotate")
                    }
                }
                stat = stat {
                    result.optJSONObject("stat")?.run {
                        aid = optLong("aid")
                        view = optInt("view")
                        danmaku = optInt("danmaku")
                        reply = optInt("reply")
                        fav = optInt("favorite")
                        coin = optInt("coin")
                        share = optInt("share")
                        nowRank = optInt("now_rank")
                        hisRank = optInt("his_rank")
                        like = optInt("like")
                        dislike = optInt("dislike")
                    }
                }
                result.run {
                    aid = optLong("aid")
                    attribute = optInt("attribute")
                    copyright = optInt("copyright")
                    ctime = optLong("ctime")
                    desc = optString("desc")
                    duration = optLong("duration")
                    firstCid = optLong("cid")
                    pic = optString("pic")
                    pubdate = optLong("pubdate")
                    redirectUrl = optString("redirect_url")
                    state = optInt("state")
                    title = optString("title")
                    typeId = optInt("tid")
                    typeName = optString("tname")
                    videos = optLong("videos")
                }
                rights = rights {
                    result.optJSONObject("rights")?.run {
                        bp = optInt("bp")
                        elec = optInt("elec")
                        download = if (sPrefs.getBoolean("allow_download", false))
                            1 else optInt("download")
                        movie = optInt("movie")
                        pay = optInt("pay")
                        hd5 = optInt("hd5")
                        noReprint = optInt("no_reprint")
                        autoplay = optInt("autoplay")
                        isCooperation = optInt("is_cooperation")
                        ugcPay = optInt("ugc_pay")
                        noBackground = if (sPrefs.getBoolean("play_arc_conf", false))
                            0 else optInt("no_background")
                    }
                }
            }
            bvid = result.optString("bvid")
            val pages = result.optJSONArray("pages")
            for (page in pages.orEmpty()) {
                this.pages += viewPage {
                    downloadSubtitle = page.optString("download_subtitle")
                    downloadTitle = page.optString("download_title")
                    this.page = page {
                        this.page = page.optInt("page")
                        page.run {
                            cid = optLong("cid")
                            from = optString("from")
                            part = optString("part")
                            duration = optLong("duration")
                            vid = optString("vid")
                            webLink = optString("weblink")
                        }
                        dimension = dimension {
                            page.optJSONObject("dimension")?.run {
                                width = optLong("width")
                                height = optLong("height")
                                rotate = optLong("rotate")
                            }
                        }
                    }
                }
            }
            shortLink = result.optString("short_link")
            result.optJSONObject("t_icon")?.let {
                for (key in it.keys()) {
                    this.tIcon[key] = tIcon {
                        icon = it.optJSONObject(key)?.optString("icon") ?: ""
                    }
                }
            }
            val tags = result.optJSONArray("tag")
            for (tag in tags.orEmpty()) {
                this.tag += tag {
                    tag.run {
                        id = optLong("tag_id")
                        name = optString("tag_name")
                        likes = optLong("likes")
                        liked = optInt("liked")
                        hates = optLong("hates")
                        hated = optInt("hated")
                        tagType = optString("tag_type")
                        uri = optString("uri")
                    }
                }
            }
            ownerExt = ownerExt {
                result.optJSONObject("owner_ext")?.run {
                    officialVerify = officialVerify {
                        optJSONObject("official_verify")?.run {
                            type = optInt("type")
                            desc = optString("desc")
                        }
                    }
                    vip = vip {
                        optJSONObject("vip")?.run {
                            vipType = optInt("vipType")
                            dueDate = optLong("vipDueDate")
                            dueRemark = optString("dueRemark")
                            accessStatus = optInt("accessStatus")
                            vipStatus = optInt("vipStatus")
                            vipStatusWarn = optString("vipStatusWarn")
                            themeType = optInt("themeType")
                            label = vipLabel {
                                optJSONObject("label")?.run {
                                    path = optString("path")
                                    text = optString("text")
                                    labelTheme = optString("label_theme")
                                }
                            }
                        }
                    }
                    fans = optLong("fans")
                    arcCount = optString("arc_count")
                }
            }
            config = config {
                result.optJSONObject("config")?.run {
                    relatesTitle = optString("relates_title")
                    abtestSmallWindow = optString("abtest_small_window")
                    recThreePointStyle = optInt("rec_three_point_style")
                    isAbsoluteTime = optBoolean("is_absolute_time")
                    relatesFeedStyle = optString("feed_style")
                    relatesFeedHasNext = optBoolean("feed_has_next")
                    localPlay = optInt("local_play")
                }
            }
        }
    }

    private fun fixView(data: Any?, urlString: String): Any? {
        val uri = Uri.parse(urlString)
        val av = uri.getQueryParameter("aid")?.takeIf {
            it != "0"
        } ?: uri.getQueryParameter("bvid")?.let {
            bv2av(it).toString()
        } ?: return null
        val queryString = uri.encodedQuery + "&id=$av"
        val content = BiliRoamingApi.getView(queryString) ?: return data
        val detailClass = instance.biliVideoDetailClass ?: return data
        val newJsonResult = content.toJSONObject().optJSONObject("v2_app_api") ?: return data
        newJsonResult.optJSONObject("season")?.optString("newest_ep_id")?.let {
            lastSeasonInfo["ep_id"] = it
        }
        return detailClass.fromJson(newJsonResult)
    }

    private fun isBangumiWithWatchPermission(result: JSONObject?, code: Int) = result?.let {
        result.optJSONObject("rights")
            ?.run { !optBoolean("area_limit", true) || optInt("area_limit", 1) == 0 }
    } ?: run { code != FAIL_CODE }


    private fun allowDownload(result: JSONObject?, toast: Boolean = true) {
        if (sPrefs.getBoolean("allow_download", false)) {
            val rights = result?.optJSONObject("rights")
            rights?.put("allow_download", 1)
            rights?.put("only_vip_download", 0)
            for (module in result?.optJSONArray("modules").orEmpty()) {
                val data = module.optJSONObject("data")
                val moduleEpisodes = data?.optJSONArray("episodes")
                for (episode in moduleEpisodes.orEmpty()) {
                    episode.optJSONObject("rights")?.put("allow_download", 1)
                }
            }
            for (section in result?.optJSONArray("prevueSection").orEmpty()) {
                for (episode in section.optJSONArray("episodes").orEmpty()) {
                    episode.optJSONObject("rights")?.put("allow_download", 1)
                }
            }
            for (episode in result?.optJSONArray("episodes").orEmpty()) {
                episode.optJSONObject("rights")?.put("allow_download", 1)
            }
            Log.d("Download allowed")
            if (toast) Log.toast("已允许下载")
        }
    }

    private fun fixEpisodesStatus(result: JSONObject?) {
        sPrefs.getString("cn_server_accessKey", null) ?: return
        if (result?.optInt("status") == 13) result.put("status", 2)
        for (module in result?.optJSONArray("modules").orEmpty()) {
            val data = module.optJSONObject("data")
            val moduleEpisodes = data?.optJSONArray("episodes")
            for (episode in moduleEpisodes.orEmpty()) {
                if (episode.optInt("status") == 13) episode.put("status", 2)
            }
        }
        for (section in result?.optJSONArray("prevueSection").orEmpty()) {
            for (episode in section.optJSONArray("episodes").orEmpty()) {
                if (episode.optInt("status") == 13) episode.put("status", 2)
            }
        }
        for (episode in result?.optJSONArray("episodes").orEmpty()) {
            if (episode.optInt("status") == 13) episode.put("status", 2)
        }
    }

    private fun setErrorMessage(activity: Activity) {
        val job = MainScope().launch {
            val id = getId("tv_desc")
            while (true) {
                if (activity.isDestroyed) break
                val tvDesc = activity.findViewById<TextView>(id)
                if (tvDesc == null) {
                    delay(500)
                    continue
                }
                tvDesc.maxLines = Int.MAX_VALUE
                (tvDesc.parent as View).setOnClickListener {
                    val lines = tvDesc.text.lines()
                    val title = lines[0]
                    val message = lines.subList(1, lines.size).joinToString("\n").trim()
                    AlertDialog.Builder(tvDesc.context)
                        .setTitle(title)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
                break
            }
        }
        MainScope().launch {
            delay(15_000)
            job.cancel()
        }
    }
}
