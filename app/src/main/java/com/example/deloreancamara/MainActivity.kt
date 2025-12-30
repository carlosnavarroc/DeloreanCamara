package com.example.deloreancamara

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Preview
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import java.io.File
import androidx.camera.core.CameraSelector
import androidx.camera.core.AspectRatio
import java.io.FileOutputStream
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.camera.view.PreviewView
import android.widget.ImageButton
import android.widget.ImageView
import android.graphics.Matrix
import android.view.MotionEvent
import android.content.ContentValues
import android.provider.MediaStore
import android.graphics.*
import android.net.Uri
import android.widget.Toast
import android.widget.SeekBar

import androidx.exifinterface.media.ExifInterface




class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayImage: ImageView
    private lateinit var btnSelectImage: ImageButton

    private lateinit var imageCapture: ImageCapture

    private val matrix = Matrix()
    private val savedMatrix = Matrix()
    private lateinit var seekBarOpacity: SeekBar

    private val NONE = 0
    private val DRAG = 1
    private val ZOOM = 2

    private var mode = NONE

    private var startX = 0f
    private var startY = 0f
    private var oldDist = 1f

    val aspectRatio = AspectRatio.RATIO_4_3



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        overlayImage = findViewById(R.id.overlayImage)
        btnSelectImage = findViewById(R.id.btnSelectImage)

        val btnSave = findViewById<ImageButton>(R.id.btnSave)

        btnSave.setOnClickListener {
            takePhoto()
        }

        seekBarOpacity = findViewById(R.id.seekBarOpacity)

        // Ajustar opacidad del overlay en tiempo real
        seekBarOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                overlayImage.alpha = progress / 100f
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 👇 1️⃣ Listener de gestos (VA AQUÍ)
        overlayImage.setOnTouchListener { _, event ->

            when (event.action and MotionEvent.ACTION_MASK) {

                MotionEvent.ACTION_DOWN -> {
                    savedMatrix.set(matrix)
                    startX = event.x
                    startY = event.y
                    mode = DRAG
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    oldDist = spacing(event)
                    if (oldDist > 10f) {
                        savedMatrix.set(matrix)
                        mode = ZOOM
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    if (mode == DRAG) {
                        matrix.set(savedMatrix)
                        matrix.postTranslate(event.x - startX, event.y - startY)
                    } else if (mode == ZOOM) {
                        val newDist = spacing(event)
                        if (newDist > 10f) {
                            matrix.set(savedMatrix)
                            val scale = newDist / oldDist
                            matrix.postScale(
                                scale,
                                scale,
                                previewView.width / 2f,
                                previewView.height / 2f
                            )
                        }
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP -> {
                    mode = NONE
                }
            }

            overlayImage.imageMatrix = matrix
            true
        }

        // 👇 2️⃣ Selector de imagen
        btnSelectImage.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        // 👇 3️⃣ Cámara
        requestCameraPermission()


    }


    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
        }
    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                overlayImage.setImageURI(it)
                overlayImage.visibility = ImageView.VISIBLE
            }
        }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .setTargetAspectRatio(aspectRatio)
                .build()

            imageCapture = ImageCapture.Builder()
                .setTargetAspectRatio(aspectRatio)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            preview.setSurfaceProvider(previewView.surfaceProvider)

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun spacing(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return kotlin.math.sqrt(x * x + y * y)
    }
    private fun takePhoto() {

        val photoFile = File(
            getExternalFilesDir(null),
            "camera_${System.currentTimeMillis()}.jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    // Foto original guardada
                    saveCombinedImage(photoFile)
                }

                override fun onError(exception: ImageCaptureException) {
                    exception.printStackTrace()
                    Toast.makeText(this@MainActivity, "Error tomando foto", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun saveCombinedImage(cameraFile: File) {

        // 1️⃣ Cargar bitmap de la foto original y rotarlo si es necesario
        var cameraBitmap = BitmapFactory.decodeFile(cameraFile.absolutePath)
        cameraBitmap = rotateBitmapIfRequired(cameraBitmap, cameraFile)
        cameraBitmap = cameraBitmap.copy(Bitmap.Config.ARGB_8888, true)

        // 2️⃣ Crear bitmap final
        val resultBitmap = Bitmap.createBitmap(
            cameraBitmap.width,
            cameraBitmap.height,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(resultBitmap)
        canvas.drawBitmap(cameraBitmap, 0f, 0f, null)

        // 3️⃣ Dibujar overlay si existe
        overlayImage.drawable?.let { drawable ->

            val overlayBitmap = Bitmap.createBitmap(
                drawable.intrinsicWidth,
                drawable.intrinsicHeight,
                Bitmap.Config.ARGB_8888
            )
            val overlayCanvas = Canvas(overlayBitmap)
            drawable.draw(overlayCanvas)

            val paint = Paint()
            paint.alpha = (overlayImage.alpha * 255).toInt()

            // Escalar overlay según PreviewView → cameraBitmap
            val scaleX = cameraBitmap.width.toFloat() / previewView.width
            val scaleY = cameraBitmap.height.toFloat() / previewView.height

            val overlayMatrix = Matrix()
            overlayMatrix.set(matrix)  // posición y zoom del usuario
            overlayMatrix.postScale(scaleX, scaleY)

            canvas.drawBitmap(overlayBitmap, overlayMatrix, paint)
        }

        try {
            // 4️⃣ Guardar imagen combinada en galería
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "combined_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/DeloreanCamara")
            }
            val uri: Uri? = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { out ->
                    resultBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                Toast.makeText(this, "Imagen combinada guardada en galería", Toast.LENGTH_LONG).show()
            }

            // 5️⃣ Guardar foto original también
            val originalValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "original_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/DeloreanCamara")
            }
            val originalUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, originalValues)
            originalUri?.let {
                contentResolver.openOutputStream(it)?.use { out ->
                    cameraBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error guardando imagen", Toast.LENGTH_LONG).show()
        }
    }

    // Función auxiliar para rotar la foto según EXIF
    private fun rotateBitmapIfRequired(bitmap: Bitmap, photoFile: File): Bitmap {
        val exif = ExifInterface(photoFile.absolutePath)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

}
