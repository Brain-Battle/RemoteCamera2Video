/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.camera2.video.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.ColorSpace
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceProfiles
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaScannerConnection
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.ConditionVariable
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.navigation.fragment.navArgs
import com.example.android.camera.utils.getPreviewOutputSize
import com.example.android.camera2.video.CameraActivity
import com.example.android.camera2.video.EncoderWrapper
import com.example.android.camera2.video.R
import com.example.android.camera2.video.databinding.FragmentPreviewBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.DataInputStream
import java.io.File
import java.net.ServerSocket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine


class PreviewFragment : Fragment() {

    private class HandlerExecutor(handler: Handler) : Executor {
        private val mHandler = handler

        override fun execute(command: Runnable) {
            if (!mHandler.post(command)) {
                throw RejectedExecutionException("" + mHandler + " is shutting down")
            }
        }
    }

    /** Android ViewBinding */
    private var _fragmentBinding: FragmentPreviewBinding? = null

    private val fragmentBinding get() = _fragmentBinding!!

    private val pipeline: Pipeline by lazy {
        if (args.useHardware) {
            HardwarePipeline(args.width, args.height, args.fps, args.filterOn, args.transfer,
                    args.dynamicRange, characteristics, encoder, fragmentBinding.viewFinder)
        } else {
            SoftwarePipeline(args.width, args.height, args.fps, args.filterOn,
                    args.dynamicRange, characteristics, encoder, fragmentBinding.viewFinder)
        }
    }

    /** AndroidX navigation arguments */
    private val args: PreviewFragmentArgs by navArgs()

    /** Host's navigation controller */
    private val navController: NavController by lazy {
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
    }

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        val context = requireContext().applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /** [CameraCharacteristics] corresponding to the provided Camera ID */
    private val characteristics: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics(args.cameraId)
    }

    /** File where the recording will be saved */
    private val outputFile: File by lazy { createFile(requireContext(), "mp4") }

    /**
     * Setup a [Surface] for the encoder
     */
    private val encoderSurface: Surface by lazy {
        encoder.getInputSurface()
    }

    /** [EncoderWrapper] utility class */
    private val encoder: EncoderWrapper by lazy { createEncoder() }

    /** [HandlerThread] where all camera operations run */
    private val cameraThread = HandlerThread("CameraThread").apply { start() }

    /** [Handler] corresponding to [cameraThread] */
    private val cameraHandler = Handler(cameraThread.looper)

    /** Captures frames from a [CameraDevice] for our video recording */
    private lateinit var session: CameraCaptureSession

    /** The [CameraDevice] that will be opened in this fragment */
    private lateinit var camera: CameraDevice

    /** Requests used for preview only in the [CameraCaptureSession] */
    private val previewRequest: CaptureRequest? by lazy {
        pipeline.createPreviewRequest(session, args.previewStabilization)
    }

    /** Requests used for preview and recording in the [CameraCaptureSession] */
    private val recordRequest: CaptureRequest by lazy {
        pipeline.createRecordRequest(session, args.previewStabilization)
    }

    private var recordingStartMillis: Long = 0L

    /** Orientation of the camera as 0, 90, 180, or 270 degrees */
    private val orientation: Int by lazy {
        characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
    }

    @Volatile
    private var recordingStarted = false

    @Volatile
    private var recordingComplete = false

    /** Condition variable for blocking until the recording completes */
    private val cvRecordingStarted = ConditionVariable(false)
    private val cvRecordingComplete = ConditionVariable(false)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentBinding = FragmentPreviewBinding.inflate(inflater, container, false)

        val window = requireActivity().getWindow()
        if (args.dynamicRange != DynamicRangeProfiles.STANDARD) {
            if (window.getColorMode() != ActivityInfo.COLOR_MODE_HDR) {
                window.setColorMode(ActivityInfo.COLOR_MODE_HDR)
            }
        }

        return fragmentBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        fragmentBinding.viewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                pipeline.destroyWindowSurface()
            }

            override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int) = Unit

            override fun surfaceCreated(holder: SurfaceHolder) {

                // Selects appropriate preview size and configures view finder
                val previewSize = getPreviewOutputSize(
                        fragmentBinding.viewFinder.display, characteristics, SurfaceHolder::class.java)
                Log.d(TAG, "View finder size: ${fragmentBinding.viewFinder.width} x ${fragmentBinding.viewFinder.height}")
                Log.d(TAG, "Selected preview size: $previewSize")
                fragmentBinding.viewFinder.setAspectRatio(previewSize.width, previewSize.height)

                pipeline.setPreviewSize(previewSize)

                // To ensure that size is set, initialize camera in the view's thread
                fragmentBinding.viewFinder.post {
                    pipeline.createResources(holder.surface)
                    initializeCamera()
                }
            }
        })
    }

    private var ipAddressTextView: TextView? = null
    private var statusTextView: TextView? = null

    private fun isCurrentlyRecording(): Boolean {
        return recordingStarted && !recordingComplete
    }

    private fun createEncoder(): EncoderWrapper {
        var width = args.width
        var height = args.height
        var orientationHint = orientation

        if (args.useHardware) {
            if (orientation == 90 || orientation == 270) {
                width = args.height
                height = args.width
            }
            orientationHint = 0
        }

        return EncoderWrapper(width, height, RECORDER_VIDEO_BITRATE, args.fps,
                args.dynamicRange, orientationHint, outputFile, args.useMediaRecorder,
                args.videoCodec)
    }

    private var job: Job? = null
    private var serverSocket: ServerSocket? = null

    /**
     * Begin all camera operations in a coroutine in the main thread. This function:
     * - Opens the camera
     * - Configures the camera session
     * - Starts the preview by dispatching a repeating request
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun initializeCamera() = lifecycleScope.launch(Dispatchers.Main) {

        // Open the selected camera
        camera = openCamera(cameraManager, args.cameraId, cameraHandler)

        // Creates list of Surfaces where the camera will output frames
        val previewTargets = pipeline.getPreviewTargets()

        // Start a capture session using our open camera and list of Surfaces where frames will go
        session = createCaptureSession(camera, previewTargets, cameraHandler,
                recordingCompleteOnClose = (pipeline !is SoftwarePipeline))

        // Sends the capture request as frequently as possible until the session is torn down or
        //  session.stopRepeating() is called
        if (previewRequest == null) {
            session.setRepeatingRequest(recordRequest, null, cameraHandler)
        } else {
            session.setRepeatingRequest(previewRequest!!, null, cameraHandler)
        }

        ipAddressTextView = view?.findViewById(R.id.ip_address_text_view)
        statusTextView = view?.findViewById(R.id.status_text_view)
        val wifiManager = view?.context?.getApplicationContext()?.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipAddress = wifiManager.connectionInfo.ipAddress
        val formattedIpAddress = String.format(
            "%d.%d.%d.%d",
            (ipAddress and 0xff),
            (ipAddress shr 8 and 0xff),
            (ipAddress shr 16 and 0xff),
            (ipAddress shr 24 and 0xff)
        )
        ipAddressTextView?.text = "IP Address: $formattedIpAddress"
        statusTextView?.text = "Port: 3000"

        job = CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket = ServerSocket(3000)
                while (true) {
                    val clientSocket = serverSocket?.accept()

                    requireActivity().requestedOrientation =
                        ActivityInfo.SCREEN_ORIENTATION_LOCKED

                    pipeline.actionDown(encoderSurface)

                    // Finalizes encoder setup and starts recording
                    encoder.start()
                    cvRecordingStarted.open()
                    if (clientSocket != null) {
                        handleClientConnection(clientSocket)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fragmentBinding.captureButton.setOnTouchListener { view, event ->
            when (event.action) {

                MotionEvent.ACTION_DOWN -> lifecycleScope.launch(Dispatchers.IO) {
                    /* If the recording was already started in the past, do nothing. */
                    if (!recordingStarted) {
                        // Prevents screen rotation during the video recording
                        requireActivity().requestedOrientation =
                                ActivityInfo.SCREEN_ORIENTATION_LOCKED

                        pipeline.actionDown(encoderSurface)

                        // Finalizes encoder setup and starts recording
                        recordingStarted = true
                        encoder.start()
                        cvRecordingStarted.open()
                        pipeline.startRecording()

                        // Start recording repeating requests, which will stop the ongoing preview
                        //  repeating requests without having to explicitly call
                        //  `session.stopRepeating`
                        if (previewRequest != null) {
                            val recordTargets = pipeline.getRecordTargets()

                            session.close()
                            session = createCaptureSession(camera, recordTargets, cameraHandler,
                                    recordingCompleteOnClose = true)

                            session.setRepeatingRequest(recordRequest,
                                    object : CameraCaptureSession.CaptureCallback() {
                                override fun onCaptureCompleted(session: CameraCaptureSession,
                                                                request: CaptureRequest,
                                                                result: TotalCaptureResult) {
                                    if (isCurrentlyRecording()) {
                                        encoder.frameAvailable()
                                    }
                                }
                            }, cameraHandler)
                        }

                        recordingStartMillis = System.currentTimeMillis()
                        Log.d(TAG, "Recording started")

                        // Set color to RED and show timer when recording begins
                        fragmentBinding.captureButton.post {
                            fragmentBinding.captureButton.background =
                                    context?.let {
                                        ContextCompat.getDrawable(it,
                                                R.drawable.ic_shutter_pressed)
                                    }
                            fragmentBinding.captureTimer?.visibility = View.VISIBLE
                            fragmentBinding.captureTimer?.stop()
                            fragmentBinding.captureTimer?.start()
                        }
                    }
                }

                MotionEvent.ACTION_UP -> lifecycleScope.launch(Dispatchers.IO) {
                    cvRecordingStarted.block()

                    /* Wait for at least one frame to process so we don't have an empty video */
                    encoder.waitForFirstFrame()

                    session.stopRepeating()
                    session.close()
                    pipeline.clearFrameListener()
                    fragmentBinding.captureButton.setOnTouchListener(null)

                    // Set color to GRAY and hide timer when recording stops
                    fragmentBinding.captureButton.post {
                        fragmentBinding.captureButton.background =
                                context?.let {
                                    ContextCompat.getDrawable(it,
                                            R.drawable.ic_shutter_normal)
                                }
                        fragmentBinding.captureTimer?.visibility = View.GONE
                        fragmentBinding.captureTimer?.stop()
                    }

                    /* Wait until the session signals onReady */
                    cvRecordingComplete.block()

                    // Unlocks screen rotation after recording finished
                    requireActivity().requestedOrientation =
                            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

                    // Requires recording of at least MIN_REQUIRED_RECORDING_TIME_MILLIS
                    val elapsedTimeMillis = System.currentTimeMillis() - recordingStartMillis
                    if (elapsedTimeMillis < MIN_REQUIRED_RECORDING_TIME_MILLIS) {
                        delay(MIN_REQUIRED_RECORDING_TIME_MILLIS - elapsedTimeMillis)
                    }

                    delay(CameraActivity.ANIMATION_SLOW_MILLIS)

                    pipeline.cleanup()

                    Log.d(TAG, "Recording stopped. Output file: $outputFile")

                    if (encoder.shutdown()) {
                        // Broadcasts the media file to the rest of the system
                        MediaScannerConnection.scanFile(
                                requireView().context, arrayOf(outputFile.absolutePath), null, null)

                        if (outputFile.exists()) {
                            // Launch external activity via intent to play video recorded using our provider
                            /*startActivity(Intent().apply {
                                action = Intent.ACTION_VIEW
                                type = MimeTypeMap.getSingleton()
                                        .getMimeTypeFromExtension(outputFile.extension)
                                val authority = "${BuildConfig.APPLICATION_ID}.provider"
                                data = FileProvider.getUriForFile(view.context, authority, outputFile)
                                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                            })*/
                        } else {
                            // TODO: 
                            //  1. Move the callback to ACTION_DOWN, activating it on the second press
                            //  2. Add an animation to the button before the user can press it again
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(activity, R.string.error_file_not_found,
                                        Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(activity, R.string.recorder_shutdown_error,
                                    Toast.LENGTH_LONG).show()
                        }
                    }
                    Handler(Looper.getMainLooper()).post {
                        navController.popBackStack()
                    }
                }
            }

            true
        }

    }

    private suspend fun handleClientConnection(clientSocket: java.net.Socket) {
        try {
            val inputStream = DataInputStream(clientSocket.getInputStream())
            while (true) {
                val inputByte = inputStream.readByte()
                when (inputByte) {
                    0.toByte() -> {
                        cvRecordingStarted.block()

                        /* Wait for at least one frame to process so we don't have an empty video */
                        encoder.waitForFirstFrame()

                        session.stopRepeating()
                        session.close()
                        pipeline.clearFrameListener()
                        fragmentBinding.captureButton.setOnTouchListener(null)

                        // Set color to GRAY and hide timer when recording stops
                        fragmentBinding.captureButton.post {
                            fragmentBinding.captureButton.background =
                                context?.let {
                                    ContextCompat.getDrawable(it,
                                        R.drawable.ic_shutter_normal)
                                }
                            fragmentBinding.captureTimer?.visibility = View.GONE
                            fragmentBinding.captureTimer?.stop()
                        }

                        /* Wait until the session signals onReady */
                        cvRecordingComplete.block()

                        // Unlocks screen rotation after recording finished
                        requireActivity().requestedOrientation =
                            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

                        // Requires recording of at least MIN_REQUIRED_RECORDING_TIME_MILLIS
                        val elapsedTimeMillis = System.currentTimeMillis() - recordingStartMillis
                        if (elapsedTimeMillis < MIN_REQUIRED_RECORDING_TIME_MILLIS) {
                            delay(MIN_REQUIRED_RECORDING_TIME_MILLIS - elapsedTimeMillis)
                        }

                        delay(CameraActivity.ANIMATION_SLOW_MILLIS)

                        pipeline.cleanup()

                        Log.d(TAG, "Recording stopped. Output file: $outputFile")

                        if (encoder.shutdown()) {
                            // Broadcasts the media file to the rest of the system
                            MediaScannerConnection.scanFile(
                                requireView().context, arrayOf(outputFile.absolutePath), null, null)

                            if (outputFile.exists()) {
                                // Launch external activity via intent to play video recorded using our provider
                                /*startActivity(Intent().apply {
                                    action = Intent.ACTION_VIEW
                                    type = MimeTypeMap.getSingleton()
                                        .getMimeTypeFromExtension(outputFile.extension)
                                    val authority = "${BuildConfig.APPLICATION_ID}.provider"
                                    data = view?.let { FileProvider.getUriForFile(it.context, authority, outputFile) }
                                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                                })*/
                            } else {
                                // TODO:
                                //  1. Move the callback to ACTION_DOWN, activating it on the second press
                                //  2. Add an animation to the button before the user can press it again
                                Handler(Looper.getMainLooper()).post {
                                    Toast.makeText(activity, R.string.error_file_not_found,
                                        Toast.LENGTH_LONG).show()
                                }
                            }
                        } else {
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(activity, R.string.recorder_shutdown_error,
                                    Toast.LENGTH_LONG).show()
                            }
                        }
                        Handler(Looper.getMainLooper()).post {
                            navController.popBackStack()
                        }
                    }
                    1.toByte() -> {
                        if (!recordingStarted) {
                            // Prevents screen rotation during the video recording
                            recordingStarted = true
                            pipeline.startRecording()

                            // Start recording repeating requests, which will stop the ongoing preview
                            //  repeating requests without having to explicitly call
                            //  `session.stopRepeating`
                            if (previewRequest != null) {
                                val recordTargets = pipeline.getRecordTargets()

                                session.close()
                                session = createCaptureSession(camera, recordTargets, cameraHandler,
                                    recordingCompleteOnClose = true)

                                session.setRepeatingRequest(recordRequest,
                                    object : CameraCaptureSession.CaptureCallback() {
                                        override fun onCaptureCompleted(session: CameraCaptureSession,
                                                                        request: CaptureRequest,
                                                                        result: TotalCaptureResult) {
                                            if (isCurrentlyRecording()) {
                                                encoder.frameAvailable()
                                            }
                                        }
                                    }, cameraHandler)
                            }

                            recordingStartMillis = System.currentTimeMillis()
                            Log.d(TAG, "Recording started")

                            // Set color to RED and show timer when recording begins
                            fragmentBinding.captureButton.post {
                                fragmentBinding.captureButton.background =
                                    context?.let {
                                        ContextCompat.getDrawable(it,
                                            R.drawable.ic_shutter_pressed)
                                    }
                                fragmentBinding.captureTimer?.visibility = View.VISIBLE
                                fragmentBinding.captureTimer?.start()
                            }
                        }
                    }
                    else -> {
                        // Unexpected byte value
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            clientSocket.close()
        }
    }

    /** Opens the camera and returns the opened device (as the result of the suspend coroutine) */
    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
            manager: CameraManager,
            cameraId: String,
            handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = cont.resume(device)

            override fun onDisconnected(device: CameraDevice) {
                Log.w(TAG, "Camera $cameraId has been disconnected")
                requireActivity().finish()
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when(error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                Log.e(TAG, exc.message, exc)
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, handler)
    }

    /**
     * Creates a [CameraCaptureSession] with the dynamic range profile set.
     */
    private fun setupSessionWithDynamicRangeProfile(
            device: CameraDevice,
            targets: List<Surface>,
            handler: Handler,
            stateCallback: CameraCaptureSession.StateCallback
    ): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val outputConfigs = mutableListOf<OutputConfiguration>()
            for (target in targets) {
                val outputConfig = OutputConfiguration(target)
                outputConfig.setDynamicRangeProfile(args.dynamicRange)
                outputConfigs.add(outputConfig)
            }

            val sessionConfig = SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                    outputConfigs, HandlerExecutor(handler), stateCallback)
            if (android.os.Build.VERSION.SDK_INT >=
                    android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                    && args.colorSpace != ColorSpaceProfiles.UNSPECIFIED) {
                sessionConfig.setColorSpace(ColorSpace.Named.values()[args.colorSpace])
            }
            device.createCaptureSession(sessionConfig)
            return true
        } else {
            device.createCaptureSession(targets, stateCallback, handler)
            return false
        }
    }

    /**
     * Creates a [CameraCaptureSession] and returns the configured session (as the result of the
     * suspend coroutine)
     */
    private suspend fun createCaptureSession(
            device: CameraDevice,
            targets: List<Surface>,
            handler: Handler,
            recordingCompleteOnClose: Boolean
    ): CameraCaptureSession = suspendCoroutine { cont ->
        val stateCallback = object: CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }

            /** Called after all captures have completed - shut down the encoder */
            override fun onClosed(session: CameraCaptureSession) {
                if (!recordingCompleteOnClose or !isCurrentlyRecording()) {
                    return
                }

                recordingComplete = true
                pipeline.stopRecording()
                cvRecordingComplete.open()
            }
        }

        setupSessionWithDynamicRangeProfile(device, targets, handler, stateCallback)
    }

    override fun onStop() {
        super.onStop()
        try {
            camera.close()
        } catch (exc: Throwable) {
            Log.e(TAG, "Error closing camera", exc)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pipeline.clearFrameListener()
        pipeline.cleanup()
        cameraThread.quitSafely()
        encoderSurface.release()
        job?.cancel()
        serverSocket?.close()
    }

    override fun onDestroyView() {
        _fragmentBinding = null
        super.onDestroyView()
    }

    companion object {
        private val TAG = PreviewFragment::class.java.simpleName

        private const val RECORDER_VIDEO_BITRATE: Int = 10_000_000
        private const val MIN_REQUIRED_RECORDING_TIME_MILLIS: Long = 1000L

        /** Creates a [File] named with the current date and time */
        private fun createFile(context: Context, extension: String): File {
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
            return File( Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "MV_Brain_Battle_${sdf.format(Date())}.$extension")
        }
    }
}
