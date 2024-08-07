package dev.sumanth.hymn

import android.Manifest
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.transition.platform.MaterialContainerTransformSharedElementCallback
import com.google.common.util.concurrent.MoreExecutors
import com.supersuman.apkupdater.ApkUpdater
import dev.sumanth.hymn.databinding.ActivityMainBinding
import dev.sumanth.hymn.databinding.EachSearchResultBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList.YouTube
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import kotlin.concurrent.thread


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var searchSuggestions = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        setExitSharedElementCallback(MaterialContainerTransformSharedElementCallback())

        window.sharedElementsUseOverlay = false
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        NewPipe.init(Downloader.getInstance())

        initMediaController()
        initViews()
        initSearch()
        checkUpdate()
        isStoragePermissionGranted()
    }

    private fun initMediaController() {
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({
            myMediaController = controllerFuture.get()
            initController()
            kioskSetup()
        }, MoreExecutors.directExecutor())
    }

    private fun initViews() {
        binding.trending.recyclerview.layoutManager = LinearLayoutManager(this)
        binding.songs.recyclerview.layoutManager = LinearLayoutManager(this)
        binding.videos.recyclerview.layoutManager = LinearLayoutManager(this)

        binding.trending.title.text = "Trending"
        binding.songs.title.text = "Songs"
        binding.videos.title.text = "Videos"
    }

    private fun initSearch() {
        binding.searchView.setupWithSearchBar(binding.searchBar)
        binding.listView.adapter = ArrayAdapter(this, androidx.appcompat.R.layout.support_simple_spinner_dropdown_item, searchSuggestions)

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

        binding.searchBar.tag = "notSearched"
        binding.searchBar.setNavigationOnClickListener {
            if (binding.searchBar.tag == "searched") {
                binding.searchBar.navigationIcon = ContextCompat.getDrawable(this, R.drawable.round_search_24)
                binding.searchBar.tag = "notSearched"
                binding.searchBar.setText("")
                kioskSetup()
            } else {
                binding.searchView.show()
            }
        }
    }

    private fun kioskSetup() {
        setProgress(true, true)
        CoroutineScope(Dispatchers.IO).launch {
            val extra = YouTube.kioskList
            val v = extra.getExtractorByUrl("https://www.youtube.com/feed/trending?bp=4gINGgt5dG1hX2NoYXJ0cw%3D%3D", null)
            v.fetchPage()
            val kioskList = mutableListOf<StreamInfoItem>()
            for (i in v.initialPage.items) {
                if ("music" in i.name.lowercase() || "song" in i.name.lowercase() || "audio" in i.name.lowercase())
                    kioskList.add(i as StreamInfoItem)
            }
            runOnUiThread {
                binding.trending.recyclerview.adapter = ResultsAdapter(kioskList, binding, myMediaController)
                setProgress(false, true)
            }
        }
    }

    private fun initController() {
        if (myMediaController?.isPlaying == true) {
            binding.miniPayer.title.text = myMediaController?.mediaMetadata?.title
            binding.miniPayer.author.text = myMediaController?.mediaMetadata?.artist
            binding.miniPayer.control.setIconResource(R.drawable.round_pause_24)
            Glide.with(binding.root).load(myMediaController?.mediaMetadata?.artworkUri).centerCrop().into(binding.miniPayer.clipart)
            binding.miniPayer.root.visibility = View.VISIBLE
        }
        binding.miniPayer.root.setOnClickListener {
            val intent = Intent(this, PlayerActivity::class.java)
            intent.putExtra("artworkUri", myMediaController?.mediaMetadata?.artworkUri.toString())
            binding.miniPayer.root.transitionName = "clipart"
            val options = ActivityOptions.makeSceneTransitionAnimation(this, binding.miniPayer.root, "clipart")
            startActivity(intent, options.toBundle())
        }
        binding.miniPayer.control.setOnClickListener {
            if (myMediaController?.isPlaying == true) {
                myMediaController?.pause()
            } else {
                myMediaController?.play()
            }
        }
        myMediaController?.addListener(object : Player.Listener {

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                super.onMediaMetadataChanged(mediaMetadata)
                binding.miniPayer.title.text = mediaMetadata.title
                binding.miniPayer.author.text = mediaMetadata.artist
                Glide.with(binding.root).load(mediaMetadata.artworkUri).centerCrop().into(binding.miniPayer.clipart)

                binding.miniPayer.progressbar.isIndeterminate = true
                binding.miniPayer.control.visibility = View.INVISIBLE
                binding.miniPayer.root.visibility = View.VISIBLE
                println("onMediaMetadataChanged")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)
                if (isPlaying) {
                    binding.miniPayer.control.setIconResource(R.drawable.round_pause_24)
                    binding.miniPayer.progressbar.isIndeterminate = false
                    binding.miniPayer.control.visibility = View.VISIBLE
                }
                else binding.miniPayer.control.setIconResource(R.drawable.round_play_arrow_24)
            }
        })
    }

    private fun showSearchResults(text: String) {
        setProgress(true, false)
        binding.searchBar.tag = "searched"
        binding.searchBar.navigationIcon = ContextCompat.getDrawable(this, R.drawable.baseline_arrow_back_24)

        CoroutineScope(Dispatchers.IO).launch {
            val music = YouTube.getSearchExtractor(text, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), null)
            val video = YouTube.getSearchExtractor(text, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), null)
            music.fetchPage()
            video.fetchPage()
            for (i in music.initialPage.items) {
                println(i.thumbnails)
            }
            runOnUiThread {
                binding.songs.recyclerview.adapter = ResultsAdapter(music.initialPage.items.subList(0, 5) as MutableList<StreamInfoItem>, binding, myMediaController)
                binding.videos.recyclerview.adapter = ResultsAdapter(video.initialPage.items.subList(0, 5) as MutableList<StreamInfoItem>, binding, myMediaController)

                setProgress(false, false)
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

    private fun setProgress(bool: Boolean, trending: Boolean) {
        if (trending) {
            binding.trending.root.visibility = View.VISIBLE
        } else {
            binding.songs.root.visibility = View.VISIBLE
            binding.videos.root.visibility = View.VISIBLE
        }

        if (bool) {
            binding.trending.root.visibility = View.GONE
            binding.songs.root.visibility = View.GONE
            binding.videos.root.visibility = View.GONE
        }

        binding.progressBar.isIndeterminate = bool
    }

    private fun isStoragePermissionGranted() {
        val perm = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && perm != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
        }
    }

}


class ResultsAdapter(private val results: MutableList<StreamInfoItem>, private val binding: ActivityMainBinding, private val mediaController: MediaController?) :
    RecyclerView.Adapter<ResultsAdapter.ViewHolder>() {
    class ViewHolder(val binding: EachSearchResultBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = EachSearchResultBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.binding.title.text = results[position].name
        holder.binding.author.text = results[position].uploaderName

        Glide.with(holder.itemView).load(results[position].thumbnails.last().url).centerCrop().into(holder.binding.clipart)
        holder.binding.download.setOnClickListener {
            downloadAudio(holder.binding.root.context, results[position].url)
        }

        holder.binding.root.setOnClickListener {

            val mediaMetadata = MediaMetadata.Builder()
            mediaMetadata.setTitle(results[position].name)
            mediaMetadata.setArtist(results[position].uploaderName)
            
            val videoId = results[position].url.substring(results[position].url.indexOf("=")+1)
            mediaMetadata.setArtworkUri(Uri.parse("https://i.ytimg.com/vi/${videoId}/maxresdefault.jpg"))

            val mediaItem = MediaItem.Builder()
            mediaItem.setMediaMetadata(mediaMetadata.build())
            mediaController?.setMediaItem(mediaItem.build())
            play(results[position].url, mediaItem)
        }
    }

    override fun getItemCount(): Int {
        return results.size
    }

    private fun downloadAudio(context: Context, videoLink: String) {
        val alertDialog = MaterialAlertDialogBuilder(context).create()
        val downloadProgessIndicator = LinearProgressIndicator(context)
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
                MaterialAlertDialogBuilder(context).setTitle("Download complete").setPositiveButton("Ok") { dialog, i -> }.show()
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

    private fun play(url: String, mediaItem: MediaItem.Builder) = CoroutineScope(Dispatchers.IO).launch {
        val extractor = YouTube.getStreamExtractor(url)
        extractor.fetchPage()
        extractor.audioStreams.sortByDescending { it.bitrate }
        withContext(Dispatchers.Main) {
            val index = mediaController?.currentMediaItemIndex!!
            mediaItem.setMediaId(extractor.audioStreams[0].content)
            mediaController.apply {
                replaceMediaItem(index, mediaItem.build())
                prepare()
                play()
            }
        }
    }
}