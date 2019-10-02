package com.example.lenovox220.fingerprintdemo

import android.arch.lifecycle.ViewModel
import android.arch.lifecycle.ViewModelProviders
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.AsyncTask
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import com.esafirm.imagepicker.features.ImagePicker
import com.machinezoo.sourceafis.FingerprintMatcher
import com.machinezoo.sourceafis.FingerprintTemplate
import com.machinezoo.sourceafis.FingerprintTransparency
import java8.util.function.Supplier
import kotlinx.android.synthetic.main.activity_main.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity() {

    lateinit var leftPath: String
    lateinit var rightPath: String
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val viewModel = ViewModelProviders.of(this)[ImageHolderViewModel::class.java]
        if(viewModel.leftImage != null) {
            leftImageView.setImageBitmap(BitmapFactory.decodeFile(viewModel.leftImage))
        }
        if(viewModel.rightImage != null) {
            rightImageView.setImageBitmap(BitmapFactory.decodeFile(viewModel.rightImage))
        }

        leftImageView.setOnClickListener {
            ImagePicker.create(this).folderMode(true).imageFullDirectory("/storage/extSdCard/VeriFinger_Sample_DB").single().start()
            getSharedPreferences("ImagePicker", Context.MODE_PRIVATE).edit().putString("Source", "Left").apply()
        }

        rightImageView.setOnClickListener {
            ImagePicker.create(this).folderMode(true).imageDirectory("/storage/extSdCard/VeriFinger_Sample_DB").single().start()
            getSharedPreferences("ImagePicker", Context.MODE_PRIVATE).edit().putString("Source", "Right").apply()
        }

        loadButton.setOnClickListener {
            ImagePicker.create(this).folderMode(true).multi().limit(2).start()
            getSharedPreferences("ImagePicker", Context.MODE_PRIVATE).edit().putString("Source", "Button").apply()
        }

        compareButton.setOnClickListener {
            ComparingTask().execute(bytesOf(leftImageView), bytesOf(rightImageView))
        }
    }

    private fun bytesOf(imagePath: String): ByteArray {
        val bitmap = BitmapFactory.decodeFile(imagePath)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
        return stream.toByteArray()
    }

    private fun bytesOf(imageView: ImageView): ByteArray {
        val drawable = imageView.drawable as BitmapDrawable
        val bitmap = drawable.bitmap
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
        return stream.toByteArray()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if(ImagePicker.shouldHandle(requestCode, resultCode, data)) {
            val source = getSharedPreferences("ImagePicker", Context.MODE_PRIVATE).getString("Source", "None")
            Log.i("ImagePicker", source ?: "None")
            if(source == "Left" && ImagePicker.getFirstImageOrNull(data) != null) {
                val path = ImagePicker.getFirstImageOrNull(data).path
                Log.i("ImagePicker", ImagePicker.getFirstImageOrNull(data).path)
                leftImageView.setImageBitmap(
                        BitmapFactory.decodeFile(path)
                )
                leftPath = ImagePicker.getFirstImageOrNull(data)?.path ?: "None"
                ViewModelProviders.of(this)[ImageHolderViewModel::class.java].leftImage = path
                Log.i("ImagePicker", "Leftmost image : $leftPath")
            }
            else if(source == "Right" && ImagePicker.getFirstImageOrNull(data) != null) {
                val path = ImagePicker.getFirstImageOrNull(data).path
                Log.i("ImagePicker", ImagePicker.getFirstImageOrNull(data).path)
                rightImageView.setImageBitmap(
                        BitmapFactory.decodeFile(ImagePicker.getFirstImageOrNull(data).path)
                )
                rightPath = ImagePicker.getFirstImageOrNull(data)?.path ?: "None"
                ViewModelProviders.of(this)[ImageHolderViewModel::class.java].rightImage = path
                Log.i("ImagePicker", "Rightmost image : $rightPath")
            }
            else if(source == "Button" && ImagePicker.getFirstImageOrNull(data) != null && ImagePicker.getImages(data).size == 2) {
                val images = ImagePicker.getImages(data)
                Log.i("ImagePicker", images.map { it.toString() }.joinToString { ", " })
                leftImageView.setImageBitmap(BitmapFactory.decodeFile(images[0].path))
                rightImageView.setImageBitmap(BitmapFactory.decodeFile(images[1].path))
                leftPath = images[0].path
                rightPath = images[1].path
                ViewModelProviders.of(this)[ImageHolderViewModel::class.java].leftImage = leftPath
                ViewModelProviders.of(this)[ImageHolderViewModel::class.java].rightImage = rightPath
                Log.i("ImagePicker", "Leftmost image : $leftPath")
                Log.i("ImagePicker", "Rightmost image : $rightPath")
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    inner class ComparingTask : AsyncTask<ByteArray, Void, Boolean>() {
        var timeLapse: Long = 0
        override fun doInBackground(vararg images: ByteArray): Boolean {
            Log.i("SourceAFIS", "Preparing algorithm...")
            val left = FingerprintTemplate().transparency(CustomLogger()).create(images[0])
            Log.i("SourceAFIS", "Left fingerprint loaded")
            val right = FingerprintTemplate().create(images[1])
            Log.i("SourceAFIS", "Right fingerprint loaded")
            val score = FingerprintMatcher().index(left).match(right)
            Log.i("SourceAFIS", "Score : $score")
            return score > 40
        }

        override fun onPreExecute() {
            super.onPreExecute()
            statusTextView.text = "..."
            loadButton.isClickable = false
            compareButton.isClickable = false
            statusTextView.text = "..."
            benchmarkTextView.text = ""
            timeLapse = System.currentTimeMillis()
        }
        override fun onPostExecute(result: Boolean?) {
            super.onPostExecute(result)
            loadButton.isClickable = true
            compareButton.isClickable = true
            timeLapse = System.currentTimeMillis() - timeLapse
            statusTextView.text = if(result!!) "Empreintes similaires" else "Empreintes différentes"
            benchmarkTextView.text = "L'opération a duré ${timeLapse / 1000} secondes"
        }
    }
}

class ImageHolderViewModel : ViewModel() {
    var leftImage: String? = null
    var rightImage: String? = null
}

class CustomLogger : FingerprintTransparency() {
    override fun log(keyword: String?, data: MutableMap<String, java.util.function.Supplier<ByteBuffer>>?) {
        if(keyword != null) {
            Log.i("SourceAFIS", keyword)
        }
    }
}

/*class ModifiedFingerprintTemplate(image: ByteArray) : FingerprintTemplate() {
    public override fun readImage(serialized: ByteArray?): DoubleMap? {
        if(serialized == null) {
            return DoubleMap(0, 0)
        }
        val bitmap = BitmapFactory.decodeByteArray(serialized, 0, serialized.size)
        val pixels = rgbFromBitmap(bitmap)
        val map = DoubleMap(bitmap.width, bitmap.height)
        for(y in 0 until bitmap.height - 1) {
            for(x in 0 until bitmap.width - 1) {
                val pixel = pixels[y * bitmap.width + x]
                val color = (pixel and 0xff) + ((pixel shr 8) and 0xff) + ((pixel shr 16) and 0xff)
                map.set(x, bitmap.height - y - 1, 1 - color * (1.0 / (3.0 * 255.0)))
            }
        }
        return map
    }

    private fun rgbFromBitmap(bitmap: Bitmap): IntArray {
        val array = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(array, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return array
    }
}*/
