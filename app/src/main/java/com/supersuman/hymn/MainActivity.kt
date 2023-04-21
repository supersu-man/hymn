package com.supersuman.hymn

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.supersuman.apkupdater.ApkUpdater
import com.supersuman.hymn.databinding.ActivityMainBinding
import com.supersuman.hymn.databinding.EachSearchResultBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList.YouTube
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeMusicSearchExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

private lateinit var player: ExoPlayer
private var thumbnailUrl = ""
private var musicTitle = ""
private var musicAuthor = ""

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = ResultsAdapter(mutableListOf(), binding)
        NewPipe.init(Downloader.getInstance())

        player = ExoPlayer.Builder(this).build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)
                if (isPlaying) {
                    binding.controlButton.setImageResource(R.drawable.pause_48px)
                } else {
                    binding.controlButton.setImageResource(R.drawable.play_arrow_48px)
                }
                CoroutineScope(Dispatchers.IO).launch {
                    val bitmap = BitmapFactory.decodeStream(URL(thumbnailUrl).openConnection().getInputStream())
                    withContext(Dispatchers.Main) { binding.image.setImageBitmap(bitmap) }
                }
                binding.title.text = musicTitle
                binding.author.text = musicAuthor
            }
        })

        initListeners()
        checkUpdate()
        isStoragePermissionGranted()
    }

    private fun initListeners() {
        binding.searchBar.addTextChangedListener {
            binding.recyclerView.adapter = ResultsAdapter(mutableListOf(), binding)
            if (it.toString().trim() == "") return@addTextChangedListener
            binding.progressBar.isIndeterminate = true
            CoroutineScope(Dispatchers.IO).launch {
                val extra = YouTube.getSearchExtractor(it.toString(), listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), null) as YoutubeMusicSearchExtractor
                extra.fetchPage()
                runOnUiThread {
                    binding.recyclerView.adapter = ResultsAdapter(extra.initialPage.items as MutableList<StreamInfoItem>, binding)
                    binding.progressBar.isIndeterminate = false
                }
            }
        }
        binding.controlButton.setOnClickListener {
            if (player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }
        }
    }

    private fun checkUpdate() {
        thread {
            val updater = ApkUpdater(this, "https://github.com/supersu-man/hymn/releases/latest")
            updater.threeNumbers = true
            if (updater.isInternetConnection() && updater.isNewUpdateAvailable() == true) {
                val dialog = MaterialAlertDialogBuilder(this).setTitle("Download new update?").setPositiveButton("Yes") { _, _ ->
                    thread { updater.requestDownload() }
                }.setNegativeButton("No") { dialogInterface, _ ->
                    dialogInterface.dismiss()
                }
                runOnUiThread { dialog.show() }
            }
        }
    }

    private fun isStoragePermissionGranted() {
        val perm = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && perm != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
        }
    }

}


class ResultsAdapter(private val results: MutableList<StreamInfoItem>, private val binding: ActivityMainBinding) : RecyclerView.Adapter<ResultsAdapter.ViewHolder>() {
    class ViewHolder(val binding: EachSearchResultBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = EachSearchResultBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.binding.title.text = results[position].name
        holder.binding.author.text = results[position].uploaderName
        CoroutineScope(Dispatchers.IO).launch {
            val url = URL(results[position].thumbnailUrl)
            val bitmap = BitmapFactory.decodeStream(url.openConnection().getInputStream())
            withContext(Dispatchers.Main) { holder.binding.image.setImageBitmap(bitmap) }
        }
        holder.binding.downloadButton.setOnClickListener {
            downloadAudio(holder.binding.root, results[position].url)
        }
        holder.binding.root.setOnClickListener {
            binding.loadingIndicator.isIndeterminate = true
            binding.title.text = ""
            binding.author.text = ""
            thumbnailUrl = results[position].thumbnailUrl
            musicTitle = results[position].name
            musicAuthor = results[position].uploaderName
            play(results[position].url)
        }
    }

    override fun getItemCount(): Int {
        return results.size
    }

    private fun downloadAudio(root: MaterialCardView, videoLink: String) {
        val alertDialog = MaterialAlertDialogBuilder(root.context).create()
        val downloadProgessIndicator = LinearProgressIndicator(root.context)
        alertDialog.setMessage("Downloading...")
        alertDialog.setView(downloadProgessIndicator, 80, 20, 80, 0)
        alertDialog.setCancelable(false)
        CoroutineScope(Dispatchers.IO).launch {
            val extractor = YouTube.getStreamExtractor(videoLink)
            extractor.fetchPage()
            extractor.audioStreams.sortByDescending { it.bitrate }
            val url = extractor.audioStreams[0].content
            val fileName = "${extractor.name}.${extractor.audioStreams[0].format}"
            val path = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Hymn/$fileName").path
            withContext(Dispatchers.Main) { alertDialog.show() }
            downloadFile(url, path) { b, c ->
                CoroutineScope(Dispatchers.Main).launch { downloadProgessIndicator.progress = (b * 100 / c).toInt() }
            }
            withContext(Dispatchers.Main) {
                downloadProgessIndicator.progress = 100
                alertDialog.dismiss()
            }
        }
    }

    private fun downloadFile(link: String, path: String, progress: ((Long, Long) -> Unit)? = null) {
        val request = Request.Builder().addHeader("Range", "bytes=0-").url(link).build()
        val response = OkHttpClient().newCall(request).execute()
        val body = response.body
        val responseCode = response.code
        if (responseCode >= HttpURLConnection.HTTP_OK && responseCode < HttpURLConnection.HTTP_MULT_CHOICE && body != null) {
            val length = body.contentLength()
            body.byteStream().apply {
                FileOutputStream(File(path)).use { output ->
                    var bytesCopied = 0L
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var bytes = read(buffer)
                    while (bytes >= 0) {
                        output.write(buffer, 0, bytes)
                        bytesCopied += bytes
                        progress?.invoke(bytesCopied, length)
                        bytes = read(buffer)
                    }
                }
            }
        }
    }

    private fun play(url: String) = CoroutineScope(Dispatchers.IO).launch {
        val extractor = YouTube.getStreamExtractor(url)
        extractor.fetchPage()
        extractor.audioStreams.sortByDescending { it.bitrate }
        withContext(Dispatchers.Main) {
            if (player.isPlaying) player.stop()
            val mediaItem = MediaItem.fromUri(extractor.audioStreams[0].content)
            player.setMediaItem(mediaItem)
            player.prepare()
            binding.loadingIndicator.isIndeterminate = false
            player.play()
        }
    }
}