package com.minosai.blindo

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.speech.tts.TextToSpeech
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import com.wonderkiln.camerakit.*
import kotlinx.android.synthetic.main.activity_main.*
import java.util.*
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private val MODEL_PATH = "mobilenet_quant_v1_224.tflite"
    private val LABEL_PATH = "labels.txt"
    private val INPUT_SIZE = 224

    private var classifier: Classifier? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler()

    private var tts: TextToSpeech? = null
    lateinit var finalText: String

    override fun onInit(status: Int) {

        if (status == TextToSpeech.SUCCESS) {

            val result = tts!!.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS","The Language specified is not supported!")
            }

        } else {
            Log.e("TTS", "Initilization Failed!")
        }

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tts = TextToSpeech(this, this)

        camera_preview.addCameraKitListener(object : CameraKitEventListener {
            override fun onEvent(cameraKitEvent: CameraKitEvent) {

            }

            override fun onError(cameraKitError: CameraKitError) {

            }

            override fun onImage(cameraKitImage: CameraKitImage) {

                var bitmap = cameraKitImage.bitmap
                bitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, false)
                val results = classifier?.recognizeImage(bitmap)
                results?.forEach {
                    Log.d("CLASSIFICATION_RESULTS", it.toString())
                }

                if(results!!.isNotEmpty()) {
                    val resultText = results[0].toString().split(" ")[1]
                    finalText = "There is a $resultText in front of you"
                    text_result.text = finalText
                }
                tts?.speak(finalText, TextToSpeech.QUEUE_FLUSH, null, "")

            }

            override fun onVideo(cameraKitVideo: CameraKitVideo) {

            }
        })

        initTensorFlowAndLoadModel()

        handler.postDelayed(object : Runnable {
            override fun run() {
                camera_preview.captureImage()
                handler.postDelayed(this, 3000)
            }
        }, 3000)
    }

    override fun onResume() {
        super.onResume()
        camera_preview.start()
    }

    override fun onPause() {
        camera_preview.stop()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.let {
            tts!!.stop()
            tts!!.shutdown()
        }
        executor.execute { classifier?.close() }
    }

    private fun initTensorFlowAndLoadModel() {
        executor.execute {
            try {
                classifier = TensorFlowImageClassifier.create(
                        assets,
                        MODEL_PATH,
                        LABEL_PATH,
                        INPUT_SIZE)
            } catch (e: Exception) {
                throw RuntimeException("Error initializing TensorFlow!", e)
            }
        }
    }

}

