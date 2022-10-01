package com.supersuman.hymn

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import com.supersuman.apkupdater.ApkUpdater
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.net.URL
import kotlin.concurrent.thread


class MainActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var searchBar: TextInputEditText
    private val headers =
        mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/79.0.3945.94 Safari/537.36")
    private lateinit var searchProgressIndicator: CircularProgressIndicator
    private var thread = Thread()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        initYoutubedl()
        initListeners()
        checkUpdate()

    }

    private fun initViews() {
        searchBar = findViewById(R.id.searchEditText)
        recyclerView = findViewById(R.id.recyclerView)
        searchProgressIndicator = findViewById(R.id.progressBar)
        searchProgressIndicator.bringToFront()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = ResultsAdapter(this, mutableListOf())
    }

    private fun initYoutubedl() {
        try {
            YoutubeDL.getInstance().init(application)
            FFmpeg.getInstance().init(this)
        } catch (e: Exception) {
            println(e)
        }
    }

    private fun initListeners() {
        searchBar.addTextChangedListener {
            searchProgressIndicator.isIndeterminate = true
            recyclerView.adapter = ResultsAdapter(this, mutableListOf())
            if (it.toString().trim() != "") {
                if (!thread.isInterrupted) thread.interrupt()
                thread = Thread {
                    try {
                        val videoIds = getVideoIds(it.toString())
                        val results = getVideoInfo(videoIds)
                        runOnUiThread {
                            recyclerView.adapter = ResultsAdapter(this, results)
                            searchProgressIndicator.isIndeterminate = false
                        }
                    } catch (e: Exception) {
                    }
                }
                thread.start()
            } else {
                searchProgressIndicator.isIndeterminate = false
            }
        }
    }

    private fun getVideoIds(searchText: String): MutableList<String> {
        val videoIds = mutableListOf<String>()
        val response = khttp.get("https://music.youtube.com/search?q=${searchText}", headers = headers).text
        val decoded = decode(response)
        val results = Regex("\\{\"videoId\":\".{11}\"\\}").findAll(decoded, 0)
        results.forEach {
            val videoId = JSONObject(it.value)["videoId"] as String
            if (videoId !in videoIds) videoIds.add(videoId)
        }
        return videoIds
    }

    private fun getVideoInfo(videoIds: MutableList<String>): MutableList<JSONObject> {
        val mutableList = mutableListOf<JSONObject>()
        videoIds.forEach {
            val videoLink = "https://noembed.com/embed?url=https://www.youtube.com/watch?v=$it"
            val response = khttp.get(videoLink, headers = headers).text
            val json = JSONObject(response)
            mutableList.add(json)
            println(json)
        }
        return mutableList
    }

    private fun decode(string: String): String {
        return string.replace("\\x22", "\"").replace("\\x28", "(").replace("\\x29", ")").replace("\\x7b", "{")
            .replace("\\x7d", "}").replace("\\x5b", "[").replace("\\x5d", "]").replace("\\x3d", "=").replace("\\/", "/")
    }

    private fun checkUpdate() {
        thread {
            val updater = ApkUpdater(this, "https://github.com/supersu-man/hymn/releases/latest")
            updater.threeNumbers = true
            if (updater.isInternetConnection() && updater.isNewUpdateAvailable() == true) {
                val dialog = MaterialAlertDialogBuilder(this)
                dialog.setTitle("Download new update?")
                dialog.setPositiveButton("Yes") { _, _ ->
                    thread {
                        updater.requestDownload()
                    }
                }
                dialog.setNegativeButton("No") { dialogInterface, _ ->
                    dialogInterface.dismiss()
                }
                runOnUiThread {
                    dialog.show()
                }
            }
        }
    }
}


class ResultsAdapter(private val activity: MainActivity, private val results: MutableList<JSONObject>) :
    RecyclerView.Adapter<ResultsAdapter.ViewHolder>() {
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title = view.findViewById<MaterialTextView>(R.id.resultTitle)
        val imageView = view.findViewById<ImageView>(R.id.resultImage)
        val author_name = view.findViewById<MaterialTextView>(R.id.resultAuthor)
        val downloadButton = view.findViewById<MaterialButton>(R.id.downloadButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.each_search_result, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.title.text = results[position]["title"] as String
        holder.author_name.text = results[position]["author_name"] as String
        thread {
            val url = URL(results[position]["thumbnail_url"] as String)
            val bitmap = BitmapFactory.decodeStream(url.openConnection().getInputStream())
            activity.runOnUiThread {
                holder.imageView.setImageBitmap(bitmap)
            }
        }
        holder.downloadButton.setOnClickListener {
            download(results[position]["url"] as String)
        }
    }

    override fun getItemCount(): Int {
        return results.size
    }

    private fun download(videoLink: String) {
        val alertDialog = MaterialAlertDialogBuilder(activity).create()
        val downloadProgessIndicator = LinearProgressIndicator(activity)
        alertDialog.setMessage("Downloading...")
        alertDialog.setView(downloadProgessIndicator, 80, 20, 80, 0)
        alertDialog.setCancelable(false)
        thread {
            try {
                activity.runOnUiThread { alertDialog.show() }
                val youtubeDLDir =
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Hymn")
                val request = YoutubeDLRequest(videoLink)
                request.addOption("-o", youtubeDLDir.absolutePath.toString() + "/%(title)s.%(ext)s")
                request.addOption("--audio-format", "mp3")
                request.addOption("-x")
                YoutubeDL.getInstance().execute(request) { progress: Float, etaInSeconds: Long ->
                    activity.runOnUiThread { downloadProgessIndicator.progress = progress.toInt() }
                    println("$progress% (ETA $etaInSeconds seconds)")
                }
                activity.runOnUiThread {
                    downloadProgessIndicator.progress = 100
                }
            } catch (e: Exception) {
            } finally {
                activity.runOnUiThread {
                    alertDialog.dismiss()
                }
            }
        }
    }

}