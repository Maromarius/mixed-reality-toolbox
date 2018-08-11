package com.kanawish.ar.robotremote

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.view.MotionEvent
import android.widget.Toast
import com.google.ar.core.Anchor
import com.google.ar.core.AugmentedImage
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState.PAUSED
import com.google.ar.core.TrackingState.STOPPED
import com.google.ar.core.TrackingState.TRACKING
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.FrameTime
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.DpToMetersViewSizer
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.rendering.ViewRenderable
import com.google.ar.sceneform.ux.ArFragment
import com.google.ar.sceneform.ux.TransformableNode
import timber.log.Timber
import java.util.concurrent.CompletableFuture

/**
 */
class ArMainActivity : AppCompatActivity() {
    data class Renderables(
            val controlRenderable: ViewRenderable,
            val labelRenderable: ViewRenderable,
            val andyRenderable: ModelRenderable
    )

    private val MIN_OPENGL_VERSION = 3.0

    private lateinit var arFragment: ArFragment

    private lateinit var renderables: Renderables

    private val augmentedImageMap = HashMap<AugmentedImage, AugmentedImageNode>()

    @SuppressLint("NewApi")
    fun initRenderables() {
        val controlsFuture = ViewRenderable.builder().setView(this, R.layout.remote_control_view).build()
        val labelFuture = ViewRenderable.builder().setView(this, R.layout.info_card_view).build()
        val andyFuture = ModelRenderable.builder().setSource(this, R.raw.andy).build()

        CompletableFuture.allOf(controlsFuture, andyFuture).handle { x, throwable ->
            if (throwable != null) {
                throw IllegalStateException(throwable)
            }
            renderables = Renderables(
                    controlsFuture.get().apply {
                        sizer = DpToMetersViewSizer(1000)
                    },
                    labelFuture.get(),
                    andyFuture.get()
            )
        }
    }

    @SuppressLint("NewApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!checkIsSupportedDeviceOrFinish(this)) {
            return
        }

        setContentView(R.layout.activity_ux)
        arFragment = supportFragmentManager.findFragmentById(R.id.ux_fragment) as ArFragment

        initRenderables()

        // Plane tap handling
        arFragment.setOnTapArPlaneListener { hitResult: HitResult, plane: Plane, motionEvent: MotionEvent ->
            if (!::renderables.isInitialized) {
                return@setOnTapArPlaneListener
            }

            // Create the Anchor.
            val anchor = hitResult.createAnchor()
            val anchorNode = AnchorNode(anchor)
            anchorNode.setParent(arFragment.arSceneView.scene)

            // Create the transformable andy and add it to the anchor.
            val andy = TransformableNode(arFragment.transformationSystem)
            andy.setParent(anchorNode)
            andy.renderable = renderables.andyRenderable
            andy.select()

            Node().apply {
                setParent(andy)
                renderable = renderables.labelRenderable
                localPosition = Vector3(0f, .25f, 0f)
            }
        }

        // Scene update frame handling
        arFragment.arSceneView.scene.addOnUpdateListener { frameTime ->
            // Only process frames when needed.
            arFragment.arSceneView.arFrame?.let { frame ->
                if (frame.camera.trackingState == TRACKING) {
                    onUpdateFrame(frame, frameTime)
                }
            }
        }
    }

    fun addControlToScene(anchor: Anchor): AnchorNode {
        val anchorNode = AnchorNode(anchor)
        anchorNode.setParent(arFragment.arSceneView.scene)
        Node().apply {
            setParent(anchorNode)
            renderable = renderables.controlRenderable
            localPosition = Vector3(0f, .04f, 0f)
        }
        return anchorNode
    }

    fun onUpdateFrame(frame: Frame, frameTime: FrameTime) {
        for (augmentedImage in frame.getUpdatedTrackables(AugmentedImage::class.java)) {
            when (augmentedImage.trackingState) {
                PAUSED -> Timber.i("Detected Image ${augmentedImage.name} / ${augmentedImage.index}")
                TRACKING -> {
                    Timber.i("Tracking Image ${augmentedImage.name} / ${augmentedImage.centerPose}")
                    // Add an image once tracking starts
                    if (!augmentedImageMap.containsKey(augmentedImage)) {
                        AugmentedImageNode(augmentedImage, addControlToScene(augmentedImage.createAnchor(augmentedImage.centerPose)))
                                .also { aiNode -> augmentedImageMap[augmentedImage] = aiNode }
                    }
                    augmentedImageMap[augmentedImage]?.controlNode?.worldPosition.let {
                        Timber.i("Tracking Image child node? ${it}")
                    }
                }
                STOPPED -> {
                    Timber.i("Image Lost ${augmentedImage.name}")
                    // gets rid of children's own children, hopefully.
                    augmentedImageMap[augmentedImage]?.controlNode?.let {
                        arFragment.arSceneView.scene.removeChild(it)
                    }
                    // Remove an image once tracking stops.
                    augmentedImageMap.remove(augmentedImage)
                }
                null -> throw IllegalStateException("Shouldn't be possible")
            }
        }
    }

    /**
     * Returns false and displays an error message if Sceneform can not run, true if Sceneform can run
     * on this device.
     *
     * Sceneform requires Android N on the device as well as OpenGL 3.0 capabilities.
     *
     * Finishes the activity if Sceneform can not run
     */
    fun checkIsSupportedDeviceOrFinish(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Timber.e("Sceneform requires Android N or later")
            Toast.makeText(activity, "Sceneform requires Android N or later", Toast.LENGTH_LONG).show()
            activity.finish()
            return false
        }
        val openGlVersionString = (activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                .deviceConfigurationInfo
                .glEsVersion
        if (java.lang.Double.parseDouble(openGlVersionString) < MIN_OPENGL_VERSION) {
            Timber.e("Sceneform requires OpenGL ES 3.0 later")
            Toast.makeText(activity, "Sceneform requires OpenGL ES 3.0 or later", Toast.LENGTH_LONG)
                    .show()
            activity.finish()
            return false
        }
        return true
    }
}
