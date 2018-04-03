package com.minosai.blindo

import android.app.Activity
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.lang.reflect.Constructor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.*
import kotlin.experimental.and

/**
 * Created by minos.ai on 03/04/18.
 */

class ImageClassifier {

    companion object {
        val DIM_IMG_SIZE_X = 224
        val DIM_IMG_SIZE_Y = 224
    }
    private val TAG = "ImageClassifier"
    private val MODEL_PATH = "mobilenet_quant_v1_224.tflite"
    private val LABEL_PATH = "labels.txt"
    private val RESULTS_TO_SHOW = 3
    private val DIM_BATCH_SIZE = 1
    private val DIM_PIXEL_SIZE = 3
    private val intValues = IntArray(DIM_IMG_SIZE_X * DIM_IMG_SIZE_Y)

    private var tflite: Interpreter? = null

    private var labelList: List<String>? = null

    private var imgData: ByteBuffer? = null

    private var labelProbArray: Array<ByteArray>? = null

    private val sortedLabels = PriorityQueue<Map.Entry<String, Float>>(
            RESULTS_TO_SHOW,
            Comparator<Map.Entry<String, Float>> { o1: Map.Entry<String, Float>, o2: Map.Entry<String, Float> ->
                o1.value.compareTo(o2.value)
            }
    )

    @Throws(IOException::class)
    constructor(activity: Activity) {
        tflite = Interpreter(loadModelFile(activity))
        labelList = loadLabelList(activity)
        imgData = ByteBuffer.allocateDirect(
                DIM_BATCH_SIZE * DIM_IMG_SIZE_X * DIM_IMG_SIZE_Y * DIM_PIXEL_SIZE)
        imgData?.order(ByteOrder.nativeOrder())
        labelProbArray = Array(1) { ByteArray(labelList!!.size) }
        Log.d(TAG, "Created a Tensorflow Lite Image Classifier.")
    }

    fun classifyFrame(bitmap: Bitmap): String {
        if (tflite == null) {
            Log.e(TAG, "Image classifier has not been initialized; Skipped.")
            return "Uninitialized Classifier."
        }
        convertBitmapToByteBuffer(bitmap)
        // Here's where the magic happens!!!
        val startTime = SystemClock.uptimeMillis()
        tflite?.run(imgData!!, labelProbArray!!)
        val endTime = SystemClock.uptimeMillis()
        Log.d(TAG, "Timecost to run model inference: " + java.lang.Long.toString(endTime - startTime))
        var textToShow = printTopKLabels()
        textToShow = java.lang.Long.toString(endTime - startTime) + "ms" + textToShow
        return textToShow
    }

    @Throws(IOException::class)
    private fun loadLabelList(activity: Activity): List<String> {
        val labelList = ArrayList<String>()
        val reader = BufferedReader(InputStreamReader(activity.assets.open(LABEL_PATH)))
        var line: String
        while (reader.readLine() != null) {
            line = reader.readLine()
            labelList.add(line)
        }
        reader.close()
        return labelList
    }

    @Throws(IOException::class)
    private fun loadModelFile(activity: Activity): MappedByteBuffer {
        val fileDescriptor = activity.assets.openFd(MODEL_PATH)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap) {
        if (imgData == null) {
            return
        }
        imgData?.rewind()
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var pixel = 0
        val startTime = SystemClock.uptimeMillis()
        for (i in 0 until DIM_IMG_SIZE_X) {
            for (j in 0 until DIM_IMG_SIZE_Y) {
                val `val` = intValues[pixel++]
                imgData?.let {
                    it.put((`val` shr 16 and 0xFF).toByte())
                    it.put((`val` shr 8 and 0xFF).toByte())
                    it.put((`val` and 0xFF).toByte())
                }
            }
        }
        val endTime = SystemClock.uptimeMillis()
        Log.d(TAG, "Timecost to put values into ByteBuffer: " + java.lang.Long.toString(endTime - startTime))
    }

    private fun printTopKLabels(): String {
        for (i in labelList!!.indices) {
            sortedLabels.add(
                    AbstractMap.SimpleEntry(labelList!![i], (labelProbArray!![0][i] and 0xff.toByte()) / 255.0f))
            if (sortedLabels.size > RESULTS_TO_SHOW) {
                sortedLabels.poll()
            }
        }
        var textToShow = ""
        val size = sortedLabels.size
        for (i in 0 until size) {
            val label = sortedLabels.poll()
            textToShow = "\n" + label.key + ":" + java.lang.Float.toString(label.value) + textToShow
        }
        return textToShow
    }

    fun close() {
        tflite?.close()
        tflite = null
    }

}