package com.example.threadsdl // 請再次確認這是您正確的包名

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// --- Gson 資料模型 ---
data class VideoInfo(
    val videoUrl: String?,
    val thumbnailUrl: String?,
    var duration: Double? = null
)
data class JsResult(val postJson: String?)

data class Post(
    val video_versions: List<VideoVersion>?,
    val image_versions2: ImageVersions?,
    val caption: Caption?,
    val carousel_media: List<CarouselMedia>?,
    val user: User?
)
data class CarouselMedia(
    val video_versions: List<VideoVersion>?,
    val image_versions2: ImageVersions?
)
data class VideoVersion(val url: String?)
data class ImageVersions(val candidates: List<Candidate>?)
data class Candidate(val url: String?)
data class Caption(val text: String?)
data class User(val username: String?, val profile_pic_url: String?)


class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ThreadsDL_Final"
    }

    // --- UI 元件 ---
    private lateinit var etUrl: EditText
    private lateinit var btnParse: Button
    private lateinit var btnPaste: Button
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var infoLayout: LinearLayout
    private lateinit var tvDescription: TextView
    private lateinit var ivAuthorProfile: ImageView
    private lateinit var tvAuthorUsername: TextView
    private lateinit var rvVideos: RecyclerView
    private lateinit var tvPostTypeMessage: TextView

    private lateinit var webView: WebView
    private lateinit var videosAdapter: VideosAdapter
    private val gson = Gson()
    private var parsingJob: Job? = null
    private lateinit var myWebViewClient: MyWebViewClient

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etUrl = findViewById(R.id.etUrl)
        btnParse = findViewById(R.id.btnParse)
        btnPaste = findViewById(R.id.btnPaste)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        infoLayout = findViewById(R.id.infoLayout)
        tvDescription = findViewById(R.id.tvDescription)
        ivAuthorProfile = findViewById(R.id.ivAuthorProfile)
        tvAuthorUsername = findViewById(R.id.tvAuthorUsername)
        rvVideos = findViewById(R.id.rvVideos)
        tvPostTypeMessage = findViewById(R.id.tvPostTypeMessage)

        setupWebView()
        setupRecyclerView()

        btnParse.setOnClickListener {
            val url = etUrl.text.toString().trim()
            if (url.isNotEmpty() && (url.contains("threads.net") || url.contains("threads.com"))) {
                startParsingProcess(url)
            } else {
                Toast.makeText(this, "請輸入有效的 Threads 連結", Toast.LENGTH_SHORT).show()
            }
        }

        btnPaste.setOnClickListener {
            pasteFromClipboard()
        }
    }

    private fun setupRecyclerView() {
        videosAdapter = VideosAdapter { videoUrl ->
            startDownload(videoUrl)
        }
        rvVideos.adapter = videosAdapter
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun setupWebView() {
        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.blockNetworkImage = false
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        myWebViewClient = MyWebViewClient()
        webView.webViewClient = myWebViewClient
    }

    private fun startParsingProcess(url: String) {
        parsingJob?.cancel()
        parsingJob = CoroutineScope(Dispatchers.Main).launch {
            loadingOverlay.visibility = View.VISIBLE
            infoLayout.visibility = View.GONE
            videosAdapter.submitList(emptyList())

            val startTime = System.currentTimeMillis()
            val result = withTimeoutOrNull(12000L) {
                parseWithWebView(url)
            }

            val elapsedTime = System.currentTimeMillis() - startTime
            val remainingDelay = 5000L - elapsedTime
            if (remainingDelay > 0) {
                delay(remainingDelay)
            }

            loadingOverlay.visibility = View.GONE

            if (result != null) {
                updateUiWithResult(result)
            } else {
                webView.stopLoading()
                Toast.makeText(this@MainActivity, "解析超時，請檢查網路或連結", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun parseWithWebView(url: String): JsResult =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            var isFinished = false

            val jsInterface = object {
                @JavascriptInterface
                fun onDataFound(jsonData: String) {
                    if (!isFinished) {
                        isFinished = true
                        val result = gson.fromJson(jsonData, JsResult::class.java)
                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    }
                }

                @JavascriptInterface
                fun onDataNotFound(reason: String) {
                    if (!isFinished) {
                        isFinished = true
                        Log.e(TAG, "JS 找不到資料: $reason")
                        if (continuation.isActive) {
                            continuation.resumeWithException(IOException(reason))
                        }
                    }
                }
            }

            runOnUiThread {
                webView.addJavascriptInterface(jsInterface, "AndroidInterface")
                myWebViewClient.setCurrentTargetUrl(url) { targetUsername ->
                    if (!isFinished) {
                        injectJavascript(targetUsername)
                    }
                }
                webView.loadUrl("about:blank")
                webView.loadUrl(url)
            }

            continuation.invokeOnCancellation {
                runOnUiThread {
                    webView.stopLoading()
                }
            }
        }

    private fun injectJavascript(targetUsername: String) {
        val jsCode = getJsToExecute(targetUsername)
        webView.evaluateJavascript(jsCode, null)
    }

    private fun getJsToExecute(targetUsername: String): String {
        return """
            (function() {
                function findObjectWithKeys(obj, keys) {
                    if (obj !== null && typeof obj === 'object') {
                        if (keys.every(key => obj.hasOwnProperty(key))) return obj;
                        for (var k in obj) {
                            var result = findObjectWithKeys(obj[k], keys);
                            if (result) return result;
                        }
                    }
                    return null;
                }
                var attempts = 0;
                var maxAttempts = 22;
                var targetUsername = "$targetUsername";
                
                var interval = setInterval(function() {
                    var scripts = document.querySelectorAll('script[type="application/json"][data-sjs]');
                    var found = false;
                    
                    scripts.forEach(function(script) {
                        if (script.textContent.includes('thread_items') && script.textContent.includes(targetUsername)) {
                            try {
                                var json = JSON.parse(script.textContent);
                                var postObject = findObjectWithKeys(json, ['pk', 'user', 'caption']);
                                
                                if (postObject && postObject.user && postObject.user.username === targetUsername) {
                                    clearInterval(interval);
                                    AndroidInterface.onDataFound(JSON.stringify({ postJson: JSON.stringify(postObject) }));
                                    found = true;
                                }
                            } catch (e) {}
                        }
                    });
                    
                    if (!found && ++attempts >= maxAttempts) {
                        clearInterval(interval);
                        AndroidInterface.onDataNotFound("找不到作者 '" + targetUsername + "' 的貼文資料");
                    }
                }, 500);
            })();
        """
    }

    private fun updateUiWithResult(result: JsResult) {
        try {
            val post = gson.fromJson(result.postJson, Post::class.java)
            var videos: MutableList<VideoInfo> = mutableListOf()
            var postType = "unknown"

            if (!post.carousel_media.isNullOrEmpty()) {
                post.carousel_media.forEach { media ->
                    if (!media.video_versions.isNullOrEmpty()) {
                        if (postType == "unknown") postType = "video"
                        videos.add(VideoInfo(
                            videoUrl = media.video_versions.first().url,
                            thumbnailUrl = media.image_versions2?.candidates?.firstOrNull()?.url
                        ))
                    }
                }
            }
            if (videos.isEmpty() && !post.video_versions.isNullOrEmpty()) {
                postType = "video"
                videos.add(VideoInfo(
                    videoUrl = post.video_versions.first().url,
                    thumbnailUrl = post.image_versions2?.candidates?.firstOrNull()?.url
                ))
            }
            if (postType == "unknown" && post.image_versions2 != null) {
                postType = "image"
            }

            tvDescription.text = post.caption?.text.takeIf { !it.isNullOrBlank() } ?: "（沒有描述）"
            Glide.with(this@MainActivity).load(post.user?.profile_pic_url).circleCrop().into(ivAuthorProfile)
            tvAuthorUsername.text = post.user?.username

            when (postType) {
                "video" -> {
                    tvPostTypeMessage.visibility = View.GONE
                    rvVideos.visibility = View.VISIBLE
                    videosAdapter.submitList(videos)
                }
                "image" -> {
                    rvVideos.visibility = View.GONE
                    tvPostTypeMessage.text = "這是一則圖片貼文，無法下載影片。"
                    tvPostTypeMessage.visibility = View.VISIBLE
                }
                else -> {
                    rvVideos.visibility = View.GONE
                    tvPostTypeMessage.visibility = View.VISIBLE
                    tvPostTypeMessage.text = "找不到可下載的內容"
                }
            }
            infoLayout.visibility = View.VISIBLE
        } catch (e: Exception) {
            Log.e(TAG, "更新 UI 時出錯", e)
            Toast.makeText(this, "處理資料時發生錯誤", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.primaryClip?.getItemAt(0)?.text?.let {
            etUrl.setText(it)
        }
    }

    private fun startDownload(url: String) {
        try {
            val fileName = "ThreadsVideo_${System.currentTimeMillis()}.mp4"
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName)
                .setDescription("正在下載 Threads 影片...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)

            videosAdapter.addDownloadedUrl(url)
            Toast.makeText(this, "已開始下載！", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "下載失敗: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    inner class MyWebViewClient : WebViewClient() {
        private var currentTargetUrl: String? = null
        private var onUrlLoaded: ((String) -> Unit)? = null

        fun setCurrentTargetUrl(url: String, onLoaded: (String) -> Unit) {
            this.currentTargetUrl = url
            this.onUrlLoaded = onLoaded
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            if (url != null && url == currentTargetUrl) {
                val targetUsername = url.substringAfter("/@").substringBefore("/")
                if (targetUsername.isNotBlank()) {
                    onUrlLoaded?.invoke(targetUsername)
                }
            }
        }
    }

    inner class VideosAdapter(private val onDownloadClick: (String) -> Unit) : RecyclerView.Adapter<VideosAdapter.VideoViewHolder>() {
        private var videoList = mutableListOf<VideoInfo>()
        private val downloadedUrls = mutableSetOf<String>()

        @SuppressLint("NotifyDataSetChanged")
        fun submitList(newList: List<VideoInfo>?) {
            videoList.clear()
            downloadedUrls.clear()
            if (newList != null) {
                videoList.addAll(newList)
            }
            notifyDataSetChanged()
        }

        fun addDownloadedUrl(url: String) {
            if (downloadedUrls.add(url)) {
                val index = videoList.indexOfFirst { it.videoUrl == url }
                if (index != -1) {
                    notifyItemChanged(index)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
            return VideoViewHolder(view)
        }

        override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
            val videoInfo = videoList[position]
            val isDownloaded = downloadedUrls.contains(videoInfo.videoUrl)
            holder.bind(videoInfo, isDownloaded)
        }

        override fun getItemCount() = videoList.size

        inner class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val thumbnail: ImageView = itemView.findViewById(R.id.ivVideoThumbnail)
            private val duration: TextView = itemView.findViewById(R.id.tvItemDuration)
            private val downloadButton: ImageButton = itemView.findViewById(R.id.btnItemDownload)

            fun bind(videoInfo: VideoInfo, isDownloaded: Boolean) {
                Glide.with(itemView.context)
                    .load(videoInfo.thumbnailUrl)
                    .placeholder(android.R.drawable.stat_sys_download_done)
                    .error(android.R.drawable.stat_notify_error)
                    .into(thumbnail)

                duration.visibility = View.GONE

                if (isDownloaded) {
                    thumbnail.alpha = 0.5f
                    downloadButton.setImageResource(android.R.drawable.stat_sys_download_done)
                    downloadButton.alpha = 0.5f
                    downloadButton.isEnabled = false
                } else {
                    thumbnail.alpha = 1.0f
                    downloadButton.setImageResource(android.R.drawable.stat_sys_download)
                    downloadButton.alpha = 1.0f
                    downloadButton.isEnabled = true
                    downloadButton.setOnClickListener {
                        videoInfo.videoUrl?.let { url ->
                            onDownloadClick(url)
                        }
                    }
                }
            }
        }
    }
}