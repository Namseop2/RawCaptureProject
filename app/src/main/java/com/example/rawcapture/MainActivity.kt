package com.example.rawcapture

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.*
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import android.os.Environment
import android.content.ContentValues
import android.provider.MediaStore
import android.widget.Toast
import android.hardware.camera2.DngCreator

class MainActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private lateinit var captureButton: Button
    private lateinit var textureView: TextureView
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var lastCaptureResult: CaptureResult? = null
    private lateinit var cameraCharacteristics: CameraCharacteristics
    private lateinit var imageReader: ImageReader
    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.textureView)
        captureButton = findViewById(R.id.captureButton)

        textureView.surfaceTextureListener = this

        captureButton.setOnClickListener {
            captureRawImage()
        }


        Log.d("Camera2", "🟢 `onCreate()` 실행됨")
    }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        openCamera()  // ✅ TextureView가 준비되었을 때 카메라 열기
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        // 필요 시 처리 (일반적으로 사용하지 않음)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        return false  // true로 변경하면 surface가 자동으로 제거됨
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        // 필요 시 처리 (일반적으로 사용하지 않음)
    }

    override fun onResume() {
        super.onResume()
        Log.d("Camera2", "🟢 앱이 다시 실행됨, 백그라운드 스레드 시작")
        startBackgroundThread()

        if (textureView.isAvailable) {
            openCamera()
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d("Camera2", "🔴 앱이 백그라운드로 이동함, 카메라 세션 종료 및 백그라운드 스레드 종료")
        closeCamera()
        stopBackgroundThread()
    }

    override fun onStop() {
        super.onStop()
        Log.d("Camera2", "🔴 앱이 완전히 중지됨, 추가 정리 작업 실행")
        closeCamera()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").apply { start() }
        backgroundHandler = Handler(backgroundThread.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread.quitSafely()
        try {
            backgroundThread.join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    private fun openCamera() {
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = manager.cameraIdList[0]
            Log.d("Camera2", "📸 카메라 ID: $cameraId 찾음")
            cameraCharacteristics = manager.getCameraCharacteristics(cameraId)

            // ✅ 현재 카메라의 해상도 가져오기
            val pixelArraySize = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val width = pixelArraySize?.width ?: 0
            val height = pixelArraySize?.height ?: 0

            Log.d("Camera2", "📸 카메라 해상도: ${width}x${height}")


            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Log.e("Camera2", "❌ CAMERA 권한이 없습니다!")
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
                return
            }

            Log.d("Camera2", "📸 카메라 열기 시작")
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    Log.d("Camera2", "✅ 카메라 열림, 세션 생성 시작")
                    setupImageReader()
                    createCameraCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    Log.e("Camera2", "❌ 카메라 연결이 끊어졌습니다.")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    Log.e("Camera2", "❌ 카메라 열기 실패: 오류 코드 $error")
                }
            }, backgroundHandler)

        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }
    private fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader.close()

            Log.d("Camera2", "✅ 카메라 및 관련 리소스 해제 완료")
        } catch (e: Exception) {
            Log.e("Camera2", "❌ 카메라 종료 중 오류 발생: ${e.message}")
        }
    }
    private fun setupImageReader() {
        val pixelArraySize = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val width = pixelArraySize?.width ?: 4000
        val height = pixelArraySize?.height ?: 3000

        Log.d("Camera2", "📸 ImageReader 해상도 설정: ${width}x${height}")

        // ✅ ImageFormat.RAW_SENSOR 사용
        imageReader = ImageReader.newInstance(width, height, ImageFormat.RAW_SENSOR, 5)

        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image == null) {
                Log.e("Camera2", "❌ onImageAvailable()에서 image가 null입니다!")
            } else {
                Log.d("Camera2", "📸 onImageAvailable()에서 이미지 가져오기 성공, 저장 시작")

                // ✅ Null 체크 후 `captureResult` 전달
                val captureResult = lastCaptureResult
                if (captureResult != null) {
                    saveRawImage(image, captureResult)
                } else {
                    Log.e("Camera2", "❌ onImageAvailable()에서 captureResult가 null입니다! 저장을 건너뜁니다.")
                }

                image.close()
            }
        }, backgroundHandler)
    }




    private fun saveRawImage(image: Image, captureResult: CaptureResult) {
        val width = image.width
        val height = image.height
        val fileName = "raw_image_${width}x${height}_${System.currentTimeMillis()}.dng"

        val outputStream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/RawCapture")
            }
            val resolver = applicationContext.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let { resolver.openOutputStream(it) }
        } else {
            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "RawCapture")
            if (!directory.exists()) {
                directory.mkdirs()
            }
            val file = File(directory, fileName)
            FileOutputStream(file)
        }

        outputStream?.use { output ->
            try {
                val dngCreator = DngCreator(cameraCharacteristics, captureResult)
                dngCreator.writeImage(output, image)
                Log.d("Camera2", "✅ DNG 파일 저장 완료: $fileName")
            } catch (e: Exception) {
                Log.e("Camera2", "❌ DNG 파일 저장 실패: ${e.message}")
            }
        }
    }






    private fun captureRawImage() {
        if (captureSession == null) {
            Log.e("Camera2", "❌ captureSession이 초기화되지 않았습니다. 촬영을 진행할 수 없습니다.")
            runOnUiThread {
                Toast.makeText(this, "카메라 준비 중입니다. 잠시 후 다시 시도하세요.", Toast.LENGTH_SHORT).show()
            }
            return
        }

        try {
            val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(imageReader.surface)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_LOCK, false)
            }

            captureSession!!.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    Log.d("Camera2", "📸 RAW 촬영 완료")
                    lastCaptureResult = result

                    // ✅ ImageReader에서 이미지 가져오기
                    val image = imageReader.acquireLatestImage()
//                    if (image == null) {
//                        Log.e("Camera2", "❌ imageReader.acquireLatestImage()가 null입니다! 이미지가 저장되지 않습니다.")
//                    }
                    image?.let {
                        // ✅ `saveRawImage()`를 올바르게 호출 (CaptureResult 전달)
                        saveRawImage(it, result)
                        it.close()
                    }
                }
            }, backgroundHandler)

        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }


    private fun createCameraCaptureSession() {
        try {
            val texture = textureView.surfaceTexture ?: return
            texture.setDefaultBufferSize(1920, 1080)
            val previewSurface = Surface(texture)

            val surfaces = listOf(previewSurface, imageReader.surface)

            Log.d("Camera2", "🔄 createCameraCaptureSession 호출됨")

            cameraDevice?.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    Log.d("Camera2", "✅ CaptureSession 설정 완료")
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e("Camera2", "❌ CaptureSession 설정 실패")
                }
            }, backgroundHandler)

        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

}
