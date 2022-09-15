package com.supersuman.hymn

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
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread


class MainActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var searchBar: TextInputEditText
    private val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/79.0.3945.94 Safari/537.36")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        initYoutubedl()
        initListeners()
        thread {
            val videoIds = getVideoIds("ud gaye")
            val videosInfo = getVideoInfo(videoIds)
            println(videosInfo)
        }
    }

    private fun initViews() {
        searchBar = findViewById(R.id.searchEditText)
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = ResultsAdapter(mutableListOf())
    }

    private fun initYoutubedl() {
        try {
            YoutubeDL.getInstance().init(application)
            FFmpeg.getInstance().init(this);

        } catch (e: Exception) {
            println(e)
        }
    }

    private fun initListeners() {
        searchBar.addTextChangedListener {
            if (it.toString().trim() == "") {
                recyclerView.adapter = ResultsAdapter(mutableListOf())
            } else {
                val videoIds = getVideoIds(it.toString())
                val videosInfo = getVideoInfo(videoIds)
            }
        }
    }

    private fun getVideoIds(searchText: String): MutableList<String> {
        val response = khttp.get("https://music.youtube.com/search?q=${searchText}", headers = headers).text
        val decoded = decode(response)
        val results = Regex("\"videoId\":\".{11}\"").findAll(decoded, 0)
        val videoIds = mutableListOf<String>()
        results.forEach {
            val videoId = Regex("\".{11}\"").find(it.value)?.value!!.replace("\"", "")
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
        }
        return mutableList
    }

    private fun decode(string: String): String {
        val decoded = string
            .replace("\\x22", "\"")
            .replace("\\x28", "(")
            .replace("\\x29", ")")
            .replace("\\x7b", "{")
            .replace("\\x7d", "}")
            .replace("\\x5b", "[")
            .replace("\\x5d", "]")
            .replace("\\x3d", "=")
            .replace("\\/", "/")
        return decoded
    }
}

class ResultsAdapter(private val results: MutableList<MutableMap<String, String>>) :
    RecyclerView.Adapter<ResultsAdapter.ViewHolder>() {
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title = view.findViewById<MaterialTextView>(R.id.resultTitle)
        val imageView = view.findViewById<ImageView>(R.id.resultImage)
        val duration = view.findViewById<MaterialTextView>(R.id.resultDuration)
        val type = view.findViewById<MaterialTextView>(R.id.resultType)
        val parentCard = view.findViewById<MaterialCardView>(R.id.resultParentCard)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.each_search_result, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.title.text = results[position]["title"]
        holder.duration.text = results[position]["duration"]
        holder.type.text = results[position]["musicVideoType"]
        holder.parentCard.setOnClickListener {
            results[position]["videoId"]?.let { it1 -> download(it1) }
        }
    }

    override fun getItemCount(): Int {
        return results.size
    }

    private fun download(videoId: String) {
        thread {
            val link = "https://www.youtube.com/watch?v=$videoId"
            val youtubeDLDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "Hymn"
            )
            val request = YoutubeDLRequest(link)
            request.addOption("-o", youtubeDLDir.absolutePath.toString() + "/%(title)s.%(ext)s")
            request.addOption("--audio-format", "mp3")
            request.addOption("-x")
            YoutubeDL.getInstance().execute(request) { progress: Float, etaInSeconds: Long ->
                println("$progress% (ETA $etaInSeconds seconds)")
            }
        }
    }

}