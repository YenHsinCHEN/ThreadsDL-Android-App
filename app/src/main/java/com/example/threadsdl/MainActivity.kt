package com.example.threadsdl // 請再次確認這是您正確的包名

import android.annotation.SuppressLint
import android.app.DownloadManager
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
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.gson.Gson
import java.util.concurrent.TimeUnit

// --- Gson 資料模型 (維持不變) ---
data class VideoInfo(
    val videoUrl: String?,
    val thumbnailUrl: String?,
    val duration: Double?
)
data class JsResult(
    val postType: String?,
    val videos: List<VideoInfo>?,
    val authorImageUrl: String?,
    val username: String?,
    val description: String?
)

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ThreadsDL_Debug"
    }

    // --- UI 元件 (維持不變) ---
    private lateinit var etUrl: EditText
    private lateinit var btnParse: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var infoLayout: LinearLayout
    private lateinit var tvDescription: TextView
    private lateinit var ivAuthorProfile: ImageView
    private lateinit var tvAuthorUsername: TextView
    private lateinit var rvVideos: RecyclerView
    private lateinit var tvPostTypeMessage: TextView

    private lateinit var webView: WebView
    private lateinit var videosAdapter: VideosAdapter
    private val gson = Gson()

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etUrl = findViewById(R.id.etUrl)
        btnParse = findViewById(R.id.btnParse)
        progressBar = findViewById(R.id.progressBar)
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
                parseUrlWithWebView(url)
            } else {
                Toast.makeText(this, "請輸入有效的 Threads 連結", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --- setupRecyclerView 和 setupWebView 函式維持不變 ---
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
        webView.addJavascriptInterface(JavaScriptInterface(), "AndroidInterface")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "WebView 頁面載入完成: $url")
                injectAdvancedJavascript()
            }
        }
    }

    private fun injectAdvancedJavascript() {
        // --- 最終修正點：採用反向匹配策略來確保縮圖正確 ---
        val jsCode = """
            (function() {
                var attempts = 0;
                var maxAttempts = 20;
                var interval = setInterval(function() {
                    var postContainer = document.querySelector('div[data-pressable-container="true"]');
                    if (!postContainer) {
                        attempts++;
                        if (attempts >= maxAttempts) {
                            clearInterval(interval);
                            AndroidInterface.onDataNotFound("找不到主要貼文容器");
                        }
                        return;
                    }

                    var videoElements = postContainer.querySelectorAll('video');
                    var imageElements = postContainer.querySelectorAll('img[draggable="false"]'); // 輪播中的圖片通常有這個屬性
                    
                    if (videoElements.length > 0 || imageElements.length > 0) {
                        clearInterval(interval);
                        
                        var result = {};
                        var videos = [];
                        
                        if (videoElements.length > 0) {
                            result.postType = "video";
                            
                            // 找到所有可見的縮圖元素 (通常在輪播結構中)
                            var thumbnailElements = postContainer.querySelectorAll('li div[role="img"] img');
                            
                            videoElements.forEach(function(video, index) {
                                var thumbnailUrl = '';
                                // 按順序匹配縮圖
                                if (thumbnailElements.length > index) {
                                    thumbnailUrl = thumbnailElements[index].src;
                                } else if (video.poster) {
                                    // 如果順序匹配失敗，嘗試用 video.poster
                                    thumbnailUrl = video.poster;
                                }
                                
                                videos.push({
                                    videoUrl: video.src,
                                    thumbnailUrl: thumbnailUrl,
                                    duration: video.duration
                                });
                            });
                            result.videos = videos;
                        } else {
                            result.postType = "image";
                        }
                        
                        var authorImage = postContainer.querySelector('img');
                        result.authorImageUrl = authorImage ? authorImage.src : '';
                        
                        var usernameElement = postContainer.querySelector('a[href*="/@"] span[dir="auto"]');
                        result.username = usernameElement ? usernameElement.innerText : '未知作者';
                        
                        var descriptionElement = postContainer.querySelector('div[data-lexical-text="true"]');
                        result.description = descriptionElement ? descriptionElement.innerText : '';
                        
                        AndroidInterface.onDataFound(JSON.stringify(result));
                        
                    } else {
                        attempts++;
                        if (attempts >= maxAttempts) {
                            clearInterval(interval);
                            AndroidInterface.onDataNotFound("在貼文容器中找不到影片或圖片");
                        }
                    }
                }, 500);
            })();
        """.trimIndent()

        webView.evaluateJavascript(jsCode, null)
    }

    // --- JavaScriptInterface 類別維持不變 ---
    inner class JavaScriptInterface {
        @JavascriptInterface
        fun onDataFound(jsonData: String) {
            val result = try {
                gson.fromJson(jsonData, JsResult::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Gson 解析失敗", e)
                null
            }

            result?.let {
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    btnParse.isEnabled = true

                    tvDescription.text = it.description.takeIf { d -> !d.isNullOrBlank() } ?: "（沒有描述）"
                    Glide.with(this@MainActivity).load(it.authorImageUrl).circleCrop().into(ivAuthorProfile)
                    tvAuthorUsername.text = it.username

                    when (it.postType) {
                        "video" -> {
                            tvPostTypeMessage.visibility = View.GONE
                            rvVideos.visibility = View.VISIBLE
                            videosAdapter.submitList(it.videos)
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
                }
            }
        }

        @JavascriptInterface
        fun onDataNotFound(reason: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "解析失敗：$reason", Toast.LENGTH_LONG).show()
                progressBar.visibility = View.GONE
                btnParse.isEnabled = true
            }
        }
    }

    // --- 剩下的函式 (parseUrlWithWebView, formatDuration, startDownload, onDestroy, VideosAdapter) 維持不變 ---
    private fun parseUrlWithWebView(url: String) {
        progressBar.visibility = View.VISIBLE
        infoLayout.visibility = View.GONE
        btnParse.isEnabled = false
        videosAdapter.submitList(emptyList())
        webView.loadUrl(url)
    }

    private fun formatDuration(seconds: Double?): String {
        if (seconds == null || seconds.isNaN() || seconds.isInfinite()) {
            return ""
        }
        val totalSeconds = seconds.toLong()
        val minutes = TimeUnit.SECONDS.toMinutes(totalSeconds)
        val remainingSeconds = totalSeconds - TimeUnit.MINUTES.toSeconds(minutes)
        return String.format("%02d:%02d", minutes, remainingSeconds)
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

    inner class VideosAdapter(private val onDownloadClick: (String) -> Unit) : RecyclerView.Adapter<VideosAdapter.VideoViewHolder>() {
        private var videoList = emptyList<VideoInfo>()

        @SuppressLint("NotifyDataSetChanged")
        fun submitList(newList: List<VideoInfo>?) {
            videoList = newList ?: emptyList()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
            return VideoViewHolder(view)
        }

        override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
            val videoInfo = videoList[position]
            holder.bind(videoInfo)
        }

        override fun getItemCount() = videoList.size

        inner class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val thumbnail: ImageView = itemView.findViewById(R.id.ivVideoThumbnail)
            private val duration: TextView = itemView.findViewById(R.id.tvItemDuration)
            private val downloadButton: ImageButton = itemView.findViewById(R.id.btnItemDownload)

            fun bind(videoInfo: VideoInfo) {
                Glide.with(itemView.context)
                    .load(videoInfo.thumbnailUrl)
                    .placeholder(android.R.drawable.stat_sys_download_done)
                    .error(android.R.drawable.stat_notify_error)
                    .into(thumbnail)

                duration.text = formatDuration(videoInfo.duration)
                duration.visibility = if (videoInfo.duration != null && videoInfo.duration > 0) View.VISIBLE else View.GONE

                downloadButton.setOnClickListener {
                    videoInfo.videoUrl?.let { url ->
                        onDownloadClick(url)
                    }
                }
            }
        }
    }
}