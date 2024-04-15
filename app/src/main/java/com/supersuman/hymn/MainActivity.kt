package com.supersuman.hymn

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.common.util.concurrent.MoreExecutors
import com.supersuman.apkupdater.ApkUpdater
import com.supersuman.hymn.databinding.ActivityMainBinding
import com.supersuman.hymn.databinding.EachSearchResultBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.Extractor
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList.YouTube
import org.schabi.newpipe.extractor.kiosk.KioskExtractor
import org.schabi.newpipe.extractor.kiosk.KioskList
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeMusicSearchExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaController: MediaController

    private var searchSuggestions = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)



        NewPipe.init(Downloader.getInstance())

        initSearch()
        checkUpdate()
        isStoragePermissionGranted()
    }

    private fun initSearch() {
        binding.searchView.setupWithSearchBar(binding.searchBar)
        binding.listView.adapter =  ArrayAdapter(this, androidx.appcompat.R.layout.support_simple_spinner_dropdown_item, searchSuggestions)

        binding.searchView.editText.addTextChangedListener {
            CoroutineScope(Dispatchers.IO).launch {
                searchSuggestions.clear()
                searchSuggestions.addAll(YouTube.suggestionExtractor.suggestionList(it.toString()))
                runOnUiThread {
                    (binding.listView.adapter as ArrayAdapter<*>).notifyDataSetChanged()
                }
            }
        }

        binding.listView.setOnItemClickListener { parent, view, position, id ->
            binding.searchBar.setText(searchSuggestions[position])
            binding.searchView.hide()
            showSearchResults(searchSuggestions[position])
        }

        binding.searchView.editText.setOnEditorActionListener { v, actionId, event ->
            binding.searchBar.setText(binding.searchView.text)
            binding.searchView.hide()
            showSearchResults(binding.searchView.text.toString())
            false
        }
    }
    private fun kioskSetup() {
        binding.trendingRecyclerview.layoutManager = LinearLayoutManager(this)
        binding.progressBar.isIndeterminate = true
        CoroutineScope(Dispatchers.IO).launch {
            val extra = YouTube.kioskList
            val v = extra.getExtractorByUrl("https://www.youtube.com/feed/trending?bp=4gINGgt5dG1hX2NoYXJ0cw%3D%3D", null)
            v.fetchPage()
            val kioskList = mutableListOf<StreamInfoItem>()
            for (i in v.initialPage.items as MutableList<StreamInfoItem>) {
                if ("music" in i.name.lowercase() || "song" in i.name.lowercase() || "audio" in i.name.lowercase())
                    kioskList.add(i)
            }
            runOnUiThread {
                binding.trendingRecyclerview.adapter = ResultsAdapter(kioskList, binding, mediaController)
                binding.progressBar.isIndeterminate = false
            }
        }

    }

    override fun onStart() {
        super.onStart()
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener(
            {
                mediaController = controllerFuture.get()
                initController()
                kioskSetup()
            }, MoreExecutors.directExecutor()
        )
    }

    private fun initController() {
        binding.controlButton.setOnClickListener {
            if (mediaController.isPlaying) {
                mediaController.pause()
            } else {
                mediaController.play()
            }
        }
        mediaController.addListener(object : Player.Listener {

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                super.onMediaMetadataChanged(mediaMetadata)
                binding.title.text = mediaMetadata.title
                binding.author.text = mediaMetadata.artist
                binding.loadingIndicator.isIndeterminate = false
                CoroutineScope(Dispatchers.IO).launch {
                    val url = URL(mediaMetadata.artworkUri.toString())
                    val bitmap = BitmapFactory.decodeStream(url.openConnection().getInputStream())
                    withContext(Dispatchers.Main) { binding.image.setImageBitmap(bitmap) }
                }
                println("onMediaMetadataChanged")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)
                if (isPlaying) binding.controlButton.setImageResource(R.drawable.pause_48px)
                else binding.controlButton.setImageResource(R.drawable.play_arrow_48px)
                println("onIsPlayingChanged")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                super.onPlaybackStateChanged(playbackState)
                println("onPlaybackStateChanged")
            }

            override fun onPlayerError(error: PlaybackException) {
                super.onPlayerError(error)
                Log.d("tag", "onPlayerError=${error.stackTraceToString()}")
            }

            override fun onPlayerErrorChanged(error: PlaybackException?) {
                super.onPlayerErrorChanged(error)
                Log.d("tag", "onPlayerErrorChanged=${error?.stackTraceToString()}")
            }
        })
    }

    private fun showSearchResults(text: String) {
        binding.musicRecyclerview.layoutManager = LinearLayoutManager(this)
        binding.videoRecyclerview.layoutManager = LinearLayoutManager(this)

        binding.progressBar.isIndeterminate = true
        binding.trendingHeading.visibility = View.GONE
        binding.trendingRecyclerview.visibility = View.GONE
        binding.musicRecyclerview.visibility = View.GONE
        binding.songsHeading.visibility = View.GONE
        binding.videoRecyclerview.visibility = View.GONE
        binding.videosHeading.visibility = View.GONE

        CoroutineScope(Dispatchers.IO).launch {
            val music = YouTube.getSearchExtractor(text, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), null)
            val video = YouTube.getSearchExtractor(text, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), null)
            music.fetchPage()
            video.fetchPage()
            runOnUiThread {
                binding.musicRecyclerview.adapter = ResultsAdapter(music.initialPage.items.subList(0, 5) as MutableList<StreamInfoItem>, binding, mediaController)
                binding.videoRecyclerview.adapter = ResultsAdapter(video.initialPage.items.subList(0, 5) as MutableList<StreamInfoItem>, binding, mediaController)

                binding.progressBar.isIndeterminate = false

                binding.musicRecyclerview.visibility = View.VISIBLE
                binding.songsHeading.visibility = View.VISIBLE
                binding.videoRecyclerview.visibility = View.VISIBLE
                binding.videosHeading.visibility = View.VISIBLE
            }
        }
    }

    private fun checkUpdate() = CoroutineScope(Dispatchers.IO).launch {
        val updater = ApkUpdater(this@MainActivity, "https://github.com/supersu-man/hymn/releases/latest")
        updater.threeNumbers = true
        if (updater.isNewUpdateAvailable() == true) {
            val dialog = MaterialAlertDialogBuilder(this@MainActivity).setTitle("Download new update?").setPositiveButton("Yes") { _, _ ->
                thread { updater.requestDownload() }
            }.setNegativeButton("No") { dialogInterface, _ ->
                dialogInterface.dismiss()
            }
            runOnUiThread { dialog.show() }
        }
    }

    private fun isStoragePermissionGranted() {
        val perm = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && perm != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
        }
    }

}


class ResultsAdapter(private val results: MutableList<StreamInfoItem>, private val binding: ActivityMainBinding, private val mediaController: MediaController) :
    RecyclerView.Adapter<ResultsAdapter.ViewHolder>() {
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
            val mediaMetadata = MediaMetadata.Builder()
            mediaMetadata.setTitle(results[position].name)
            mediaMetadata.setArtist(results[position].uploaderName)
            mediaMetadata.setArtworkUri(Uri.parse(results[position].thumbnailUrl))
            play(results[position].url, mediaMetadata.build())
        }
    }

    override fun getItemCount(): Int {
        return results.size
    }

    private fun downloadAudio(root: MaterialCardView, videoLink: String) {
        val alertDialog = MaterialAlertDialogBuilder(root.context).create()
        val downloadProgessIndicator = LinearProgressIndicator(root.context)
        alertDialog.setTitle("Downloading...")
        alertDialog.setView(downloadProgessIndicator, 80, 20, 80, 0)
        alertDialog.setCancelable(false)
        CoroutineScope(Dispatchers.IO).launch {
            val extractor = YouTube.getStreamExtractor(videoLink)
            extractor.fetchPage()
            extractor.audioStreams.sortByDescending { it.bitrate }
            val url = extractor.audioStreams[0].content
            val fileName = "${extractor.name}.${extractor.audioStreams[0].format}"
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Hymn").mkdir()
            val path = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Hymn/$fileName").path
            withContext(Dispatchers.Main) { alertDialog.show() }
            downloadFile(url, path) { b, c ->
                CoroutineScope(Dispatchers.Main).launch { downloadProgessIndicator.progress = (b * 100 / c).toInt() }
            }
            withContext(Dispatchers.Main) {
                downloadProgessIndicator.progress = 100
                alertDialog.dismiss()
                MaterialAlertDialogBuilder(root.context).setTitle("Download complete").setPositiveButton("Ok") { dialog, i -> }.show()
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

    private fun play(url: String, metaData: MediaMetadata) = CoroutineScope(Dispatchers.IO).launch {
        val extractor = YouTube.getStreamExtractor(url)
        extractor.fetchPage()
        extractor.audioStreams.sortByDescending { it.bitrate }
        withContext(Dispatchers.Main) {
            val mediaItem = MediaItem.Builder()
            mediaItem.setMediaMetadata(metaData)
            mediaItem.setMediaId(extractor.audioStreams[0].content)
            mediaController.apply {
                setMediaItem(mediaItem.build())
                prepare()
                play()
            }
        }
    }
}