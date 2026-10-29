// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import android.util.Log
import com.metallic.chiaki.lib.Session
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * Pulls the decoded game audio from [session], runs offline speech recognition (Vosk)
 * and reports the current subtitle line through [onText] (from a background thread).
 * Empty text means "hide the subtitle".
 */
class LiveSubtitles(private val session: Session, private val modelDir: File, private val onText: (String) -> Unit)
{
	companion object
	{
		private const val TAG = "LiveSubtitles"
		private const val CLEAR_AFTER_MS = 4000L

		private var cachedModel: Model? = null
		private var cachedModelPath: String? = null

		/** Loading takes seconds, so keep one model per process. Returns null if [dir] holds no model. */
		@Synchronized
		fun loadModel(dir: File): Model?
		{
			if(cachedModelPath == dir.absolutePath)
				return cachedModel
			if(!File(dir, "am").isDirectory && !File(dir, "conf").isDirectory)
			{
				Log.w(TAG, "No Vosk model in $dir, live subtitles disabled")
				return null
			}
			return try
			{
				Model(dir.absolutePath).also {
					cachedModel?.close()
					cachedModel = it
					cachedModelPath = dir.absolutePath
				}
			}
			catch(e: Exception)
			{
				Log.e(TAG, "Failed to load Vosk model from $dir", e)
				null
			}
		}

		/** Interleaved [channels] samples to mono, average of all channels. Returns mono sample count. */
		fun downmix(src: ShortArray, count: Int, channels: Int, dst: ShortArray): Int
		{
			if(channels <= 1)
			{
				System.arraycopy(src, 0, dst, 0, count)
				return count
			}
			val frames = count / channels
			for(f in 0 until frames)
			{
				var sum = 0
				for(c in 0 until channels)
					sum += src[f * channels + c]
				dst[f] = (sum / channels).toShort()
			}
			return frames
		}
	}

	@Volatile private var running = false
	private var thread: Thread? = null

	fun start()
	{
		if(running)
			return
		running = true
		thread = Thread(::run, TAG).also { it.start() }
	}

	/** Blocks until the worker has stopped touching [session]; call before disposing it. */
	fun stop()
	{
		running = false
		thread?.join()
		thread = null
	}

	private fun run()
	{
		val model = loadModel(modelDir) ?: return
		// wait for the audio header to know the stream format
		while(running && (session.audioRate == 0 || session.audioChannels == 0))
			Thread.sleep(100)
		if(!running)
			return
		val rate = session.audioRate
		val channels = session.audioChannels
		Log.i(TAG, "Recognizing $channels ch @ $rate Hz")

		// Vosk resamples internally, we only need mono
		val recognizer = Recognizer(model, rate.toFloat())
		val buf = ShortArray(rate * channels / 10) // 100ms
		val mono = ShortArray(buf.size)
		var lastText = ""
		var lastTextTime = 0L
		try
		{
			while(running)
			{
				val read = session.readAudio(buf)
				val now = System.currentTimeMillis()
				if(read <= 0)
				{
					Thread.sleep(20)
				}
				else
				{
					val n = downmix(buf, read, channels, mono)
					val final = recognizer.acceptWaveForm(mono, n)
					val text = if(final)
						JSONObject(recognizer.result).optString("text")
					else
						JSONObject(recognizer.partialResult).optString("partial")
					// an empty partial right after a final result must not wipe the line
					if(text.isNotBlank() && text != lastText)
					{
						lastText = text
						onText(text)
					}
					if(text.isNotBlank())
						lastTextTime = now
				}
				if(lastText.isNotEmpty() && now - lastTextTime > CLEAR_AFTER_MS)
				{
					lastText = ""
					onText("")
				}
			}
		}
		finally
		{
			recognizer.close()
			onText("")
		}
	}
}
