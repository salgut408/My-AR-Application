package com.sgut.android.myarapplication

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.ar.core.*
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.ArCoreApk.InstallStatus
import com.google.ar.core.exceptions.*
import com.sgut.android.myarapplication.databinding.ActivityMainBinding
import com.sgut.android.myarapplication.helpers.*
import com.sgut.android.myarapplication.rendering.*
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    private val TAG: String = MainActivity::class.java.simpleName
    private var installRequested = false
    private var mode: Mode = Mode.VIKING
    private  var session: Session? = null

    //top & UI

    lateinit var binding : ActivityMainBinding

    private lateinit var gestureDetector: GestureDetector
    private lateinit var trackingStateHelper: TrackingStateHelper
    private lateinit var displayRotationHelper: DisplayRotationHelper
    private val messageSnackbarHelper: SnackbarHelper = SnackbarHelper()
    private val backgroundRenderer: BackgroundRenderer = BackgroundRenderer()
    private val planeRenderer: PlaneRenderer = PlaneRenderer()
    private val pointCloudRenderer: PointCloudRenderer = PointCloudRenderer()

    //define each prop as an objRenderer & adding Plane attachment properties
    // mustache
    private val augmentedFaceRenderer = AugmentedFaceRenderer()
    private val noseObject = ObjectRenderer

    private val vikingObject = ObjectRenderer()
    private val cannonObject = ObjectRenderer()
    private val targetObject = ObjectRenderer()
    //plane atchments - created when user taps screen
    private var vikingAttachment: PlaneAttachment? = null
    private var cannonAttachment: PlaneAttachment? = null
    private var targetAttachment: PlaneAttachment? = null

    // Temp matrix allocated here 2 reduce # of allocations and taps for each frame
    private val noseMatrix = FloatArray(16)
    private val DEFAULT_COLOR = floatArrayOf(0f, 0f, 0f, 0f)

    private val maxAllocationSize = 16
    private val anchorMatrix = FloatArray(maxAllocationSize)
    private val queuedSingleTaps = ArrayBlockingQueue<MotionEvent>(maxAllocationSize)



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        trackingStateHelper = TrackingStateHelper(this@MainActivity)
        displayRotationHelper = DisplayRotationHelper(this@MainActivity)

        installRequested = false

        setupTapDetector()
        setupSurfaceView()

    }

    fun onRadioButtonClicked(view: View) {
        when(view.id) {
            R.id.radioCannon -> mode = Mode.CANNON
            R.id.radioTarget -> mode = Mode.TARGET
            else -> mode = Mode.VIKING
        }
    }

    private fun setupSurfaceView() {
        // Set up renderer.
       binding.surfaceView.preserveEGLContextOnPause = true
        binding.surfaceView.setEGLContextClientVersion(2)
        binding.surfaceView.setEGLConfigChooser(8, 8, 8, 8, maxAllocationSize, 0)
        binding.surfaceView.setRenderer(this)
        binding.surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        binding.surfaceView.setWillNotDraw(false)
        binding.surfaceView.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }
    }

    private fun setupTapDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                onSingleTap(e)
                return true
            }

            override fun onDown(e: MotionEvent): Boolean {
                return true
            }
        })
    }

    private fun onSingleTap(e: MotionEvent) {
        // Queue tap if there is space. Tap is lost if queue is full.
        queuedSingleTaps.offer(e)
    }

    override fun onResume() {
        super.onResume()

        if (session == null) {
            if(!setupSession()){
                return
            }
        }
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            messageSnackbarHelper.showError(this@MainActivity, "Camera not available try to restart the app")
            session = null
            return
        }
        binding.surfaceView.onResume()
        displayRotationHelper.onResume()
    }

    private fun setupSession(): Boolean {
        var exception: Exception? = null
        var message: String? = null

        try {
            when (ArCoreApk.getInstance().requestInstall(this@MainActivity, !installRequested)) {
                InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    return false
                }
                InstallStatus.INSTALLED -> {
                }
                else -> {
                    message = "Ar core failed"
                }
            }

            // Requesting Camera Permission
            if (!CameraPermissionHelper.hasCameraPermission(this@MainActivity)) {
                CameraPermissionHelper.requestCameraPermission(this@MainActivity)
                return false
            }

            // Create the session.
            session = Session(this@MainActivity)
            //camera config moment

//            val filter = CameraConfigFilter(session).setFacingDirection(CameraConfig.FacingDirection.FRONT)
//            val cameraConfig = session!!.getSupportedCameraConfigs(filter)[0]
//             session!!.cameraConfig = cameraConfig



        } catch (e: UnavailableArcoreNotInstalledException) {
            message = "Please install ARCore"
            exception = e
        } catch (e: UnavailableUserDeclinedInstallationException) {
            message = "Please install ARCore"
            exception = e
        } catch (e: UnavailableApkTooOldException) {
            message = "Please update ARCore"
            exception = e
        } catch (e: UnavailableSdkTooOldException) {
            message = "Please update app"
            exception = e
        } catch (e: UnavailableDeviceNotCompatibleException) {
            message = "ARCore not supported"
            exception = e
        } catch (e: Exception) {
            message = "failed to create ar session"
            exception = e
        }

        if (message != null) {
            messageSnackbarHelper.showError(this@MainActivity, message)
            Log.e(TAG, "failed to create ar session", exception)
            return false
        }

        return true
    }

    override fun onPause() {
        super.onPause()
        if(session != null) {
            displayRotationHelper.onPause()
            binding.surfaceView.onPause()
            session!!.pause()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        results: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (!CameraPermissionHelper.hasCameraPermission(this@MainActivity)) {
            Toast.makeText(
                this@MainActivity,
                "camera_permission_needed",
                Toast.LENGTH_LONG
            ).show()

            // Permission denied with checking "Do not ask again".
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this@MainActivity)) {
                CameraPermissionHelper.launchPermissionSettings(this@MainActivity)
            }
            finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        FullScreenHelper.setFullScreenOnWindowFocusChanged(this@MainActivity, hasFocus)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

        // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
        try {
            // Create the texture and pass it to ARCore session to be filled during update().
            backgroundRenderer.createOnGlThread(this@MainActivity)
            planeRenderer.createOnGlThread(this@MainActivity, getString(R.string.model_grid_png))
            pointCloudRenderer.createOnGlThread(this@MainActivity)

            // set up the objects
            //1-using 3d files from proj to set up objs
            vikingObject.createOnGlThread(this@MainActivity,getString(R.string.model_viking_obj), getString(R.string.model_viking_png))
            cannonObject.createOnGlThread(this@MainActivity,getString(R.string.model_cannon_obj), getString(R.string.model_cannon_png))
            targetObject.createOnGlThread(this@MainActivity,getString(R.string.model_target_obj), getString(R.string.model_target_png))

            // 2 - setting ambient/diffuse/spectacular-power on each obj
            targetObject.setMaterialProperties(0.0f, 3.5f, 1.0f, 6.0f)
            vikingObject.setMaterialProperties(0.0f, 3.5f, 1.0f, 6.0f)
            cannonObject.setMaterialProperties(0.0f, 3.5f, 1.0f, 6.0f)


        } catch (e: IOException) {
            Log.e(TAG, "Failed to create Asset", e)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        displayRotationHelper.onSurfaceChanged(width, height)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        // Clear screen to notify driver it should not load any pixels from previous frame.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        session?.let {
            // Notify ARCore session that the view size changed
            displayRotationHelper.updateSessionIfNeeded(it)

            try {
                it.setCameraTextureName(backgroundRenderer.textureId)

                val frame = it.update()
                val camera = frame.camera

                // Handle one tap per frame.
                handleTap(frame, camera)
                drawBackground(frame)

                // Keeps the screen unlocked while tracking, but allow it to lock when tracking stops.
                trackingStateHelper.updateKeepScreenOnFlag(camera.trackingState)

                // If not tracking, don't draw 3D objects, show tracking failure reason instead.
                if (!isInTrackingState(camera)) return

                val projectionMatrix = computeProjectionMatrix(camera)
                val viewMatrix = computeViewMatrix(camera)
                val lightIntensity = computeLightIntensity(frame)

                visualizeTrackedPoints(frame, projectionMatrix, viewMatrix)
                checkPlaneDetected()
                visualizePlanes(camera, projectionMatrix)

                // : Call drawObject() for Viking, Cannon and Target here

                drawObject(
                    vikingObject,
                    vikingAttachment,
                    Mode.VIKING.scaleFactor,
                    projectionMatrix,
                    viewMatrix,
                    lightIntensity
                )

                drawObject(
                    cannonObject,
                    cannonAttachment,
                    Mode.CANNON.scaleFactor,
                    projectionMatrix,
                    viewMatrix,
                    lightIntensity
                )

                drawObject(
                    targetObject,
                    targetAttachment,
                    Mode.TARGET.scaleFactor,
                    projectionMatrix,
                    viewMatrix,
                    lightIntensity
                )


            } catch (t: Throwable) {
                Log.e(TAG, "exception_on_opengl", t)
            }
        }
    }

    private fun isInTrackingState(camera: Camera): Boolean {
        if (camera.trackingState == TrackingState.PAUSED) {
            messageSnackbarHelper.showMessage(
                this@MainActivity, TrackingStateHelper.getTrackingFailureReasonString(camera)
            )
            return false
        }

        return true
    }
    private fun drawObject(
        objectRenderer: ObjectRenderer,
        planeAttachment: PlaneAttachment?,
        scaleFactor: Float,
        projectionMatrix: FloatArray,
        viewMatrix: FloatArray,
        lightIntensity: FloatArray,
    ) {
        if (planeAttachment?.isTracking == true) {
            planeAttachment.pose.toMatrix(anchorMatrix, 0)

            // Update and draw the model
            objectRenderer.updateModelMatrix(anchorMatrix, scaleFactor)
            objectRenderer.draw(viewMatrix, projectionMatrix, lightIntensity)
        }
    }

    private fun drawBackground(frame: Frame) {
        backgroundRenderer.draw(frame)
    }

    private fun computeProjectionMatrix(camera: Camera): FloatArray {
        val projectionMatrix = FloatArray(maxAllocationSize)
        camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)

        return projectionMatrix
    }

    private fun computeViewMatrix(camera: Camera): FloatArray {
        val viewMatrix = FloatArray(maxAllocationSize)
        camera.getViewMatrix(viewMatrix, 0)

        return viewMatrix
    }

    /**
     * Compute lighting from average intensity of the image.
     */
    private fun computeLightIntensity(frame: Frame): FloatArray {
        val lightIntensity = FloatArray(4)
        frame.lightEstimate.getColorCorrection(lightIntensity, 0)

        return lightIntensity
    }
    /**
     * Visualizes tracked points.
     */
    private fun visualizeTrackedPoints(
        frame: Frame,
        projectionMatrix: FloatArray,
        viewMatrix: FloatArray,
    ) {
        // Use try-with-resources to automatically release the point cloud.
        frame.acquirePointCloud().use { pointCloud ->
            pointCloudRenderer.update(pointCloud)
            pointCloudRenderer.draw(viewMatrix, projectionMatrix)
        }
    }

    /**
     *  Visualizes planes.
     */
    private fun visualizePlanes(camera: Camera, projectionMatrix: FloatArray) {
        planeRenderer.drawPlanes(
            session!!.getAllTrackables(Plane::class.java),
            camera.displayOrientedPose,
            projectionMatrix
        )
    }

    /**
     * Checks if any tracking plane exists then, hide the message UI, otherwise show searchingPlane message.
     */
    private fun checkPlaneDetected() {
        if (hasTrackingPlane()) {
            messageSnackbarHelper.hide(this@MainActivity)
        } else {
            messageSnackbarHelper.showMessage(
                this@MainActivity,
                "searching_for_surfaces"
            )
        }
    }

    /**
     * Checks if we detected at least one plane.
     */
    private fun hasTrackingPlane(): Boolean {
        val allPlanes = session!!.getAllTrackables(Plane::class.java)

        for (plane in allPlanes) {
            if (plane.trackingState == TrackingState.TRACKING) {
                return true
            }
        }

        return false
    }

    /**
     * Handle a single tap per frame
     */
    private fun handleTap(frame: Frame, camera: Camera) {
        val tap = queuedSingleTaps.poll()

        if (tap != null && camera.trackingState == TrackingState.TRACKING) {
            // Check if any plane was hit, and if it was hit inside the plane polygon
            for (hit in frame.hitTest(tap)) {
                val trackable = hit.trackable

                if ((trackable is Plane
                            && trackable.isPoseInPolygon(hit.hitPose)
                            && PlaneRenderer.calculateDistanceToPlane(hit.hitPose, camera.pose) > 0)
                    || (trackable is Point
                            && trackable.orientationMode
                            == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL)
                ) {
                    when (mode) {
                        Mode.VIKING -> vikingAttachment = addSessionAnchorFromAttachment(vikingAttachment, hit)
                        Mode.CANNON -> cannonAttachment = addSessionAnchorFromAttachment(cannonAttachment, hit)
                        Mode.TARGET -> targetAttachment = addSessionAnchorFromAttachment(targetAttachment, hit)
                    }
                    // TODO: Create an anchor if a plane or an oriented point was hit
                    break
                }
            }
        }
    }

    // : Add addSessionAnchorFromAttachment() function here
    private fun addSessionAnchorFromAttachment(
        previousAttachment: PlaneAttachment?, hit: HitResult,
    ): PlaneAttachment? {
        //1
        previousAttachment?.anchor?.detach()
        //2
        val plane = hit.trackable as Plane
        val anchor = session!!.createAnchor(hit.hitPose)
        //3
        return PlaneAttachment(plane, anchor)

    }

}