package com.supersuman.hymn

import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.Player
import com.bumptech.glide.Glide
import com.google.android.material.transition.platform.MaterialContainerTransform
import com.google.android.material.transition.platform.MaterialContainerTransformSharedElementCallback
import com.supersuman.hymn.databinding.ActivityPlayerBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.timerTask


class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var task: TimerTask
    private lateinit var timer: Timer
    private val playerListener = object: Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            super.onIsPlayingChanged(isPlaying)
            if (isPlaying) {
                binding.control.setIconResource(R.drawable.round_pause_24)
                timer = Timer()
                task = timerTask {
                    CoroutineScope(Dispatchers.Main).launch {
                        binding.seekbar.progress = ((myMediaController?.currentPosition!!.toDouble() / myMediaController?.duration!!.toDouble()) * 100).toInt()
                    }
                }
                timer.schedule(task, 0, 1000)
            }
            else {
                binding.control.setIconResource(R.drawable.round_play_arrow_24)
                timer.cancel()
            }
        }
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        findViewById<View>(android.R.id.content).transitionName = "clipart"
        setEnterSharedElementCallback(MaterialContainerTransformSharedElementCallback())

        window.sharedElementEnterTransition = MaterialContainerTransform().apply {
            addTarget(android.R.id.content)
            duration = 500L
        }
        window.sharedElementReturnTransition = MaterialContainerTransform().apply {
            addTarget(android.R.id.content)
            duration = 250L
        }
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.title.text = myMediaController?.mediaMetadata?.title
        binding.artist.text = myMediaController?.mediaMetadata?.artist
        Glide.with(binding.root).load(myMediaController?.mediaMetadata?.artworkUri).centerCrop().into(binding.clipart)


        if (myMediaController?.isPlaying == true) {
            binding.control.setIconResource(R.drawable.round_pause_24)
            timer = Timer()
            task = timerTask {
                CoroutineScope(Dispatchers.Main).launch {
                    binding.seekbar.progress = ((myMediaController?.currentPosition!!.toDouble() / myMediaController?.duration!!.toDouble()) * 100).toInt()
                }
            }
            timer.schedule(task, 0, 1000)
        } else {
            binding.control.setIconResource(R.drawable.round_play_arrow_24)
            binding.seekbar.progress = ((myMediaController?.currentPosition!!.toDouble() / myMediaController?.duration!!.toDouble()) * 100).toInt()
        }

        myMediaController?.addListener(playerListener)

        binding.control.setOnClickListener {
            if (myMediaController?.isPlaying == true) {
                myMediaController?.pause()
            } else {
                myMediaController?.play()
            }
        }

        binding.seekbar.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    myMediaController!!.seekTo(((progress*myMediaController?.duration!!).toDouble()/100).toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                myMediaController?.pause()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                myMediaController?.play()
            }

        })
    }

    override fun onDestroy() {
        super.onDestroy()
        myMediaController?.removeListener(playerListener)
    }
}