package com.example.projet_pmr

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Point
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.vision.barcode.Barcode
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import io.github.sceneview.ar.ArSceneView
import io.github.sceneview.ar.arcore.LightEstimationMode
import io.github.sceneview.ar.node.ArModelNode
import io.github.sceneview.ar.node.PlacementMode
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Suppress("DEPRECATION")
@ExperimentalGetImage
class NavActivity : AppCompatActivity() {

    private lateinit var sceneView: ArSceneView
    private lateinit var modelNode: ArModelNode
    private lateinit var scanButton: Button


    private lateinit var cameraExecutor: ExecutorService
    private lateinit var barcodeScanner: BarcodeScanner
    private var isModelPlaced = false

    private val handler = Handler()
    private var isCameraRunning = false
    private var imageAnalysis: ImageAnalysis? = null
    private lateinit var cameraProvider: ProcessCameraProvider

    private val startDelay = 2000L // Délai initial de 2 secondes
    private val stopDelay = 500L // Durée pendant laquelle la caméra reste active
    private var scene = false

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nav)

        sceneView = findViewById<ArSceneView>(R.id.sceneView).apply {
            this.lightEstimationMode = LightEstimationMode.ENVIRONMENTAL_HDR_NO_REFLECTIONS
        }

        scanButton = findViewById<Button>(R.id.scanButton)
        scanButton.setOnClickListener {
            if (allPermissionsGranted()) {
                startCameraWithDelay()
            } else {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.CAMERA), REQUEST_CODE_PERMISSIONS
                )
            }
        }
        // Initialize the camera executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Initialize the barcode scanner
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        barcodeScanner = BarcodeScanning.getClient(options)

        val returnToMapButton : Button = findViewById(R.id.returnToMapButton)
        returnToMapButton.setOnClickListener {
            finish()
        }
    }

    private fun allPermissionsGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        if (isCameraRunning) return

        val intent = intent
        val navigation = intent.getSerializableExtra("optimalPath") as List<List<Point>> //Récupération de l'itinéraire
        val currentStep = intent.getIntExtra("currentStep",0)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis!!.setAnalyzer(cameraExecutor, BarcodeAnalyzer { barcode ->
                // Process the scanned QR code
                Log.d(TAG, "Scanned QR Code: $barcode")
                runOnUiThread {
                    //Toast.makeText(this, "Scanned QR Code: $barcode", Toast.LENGTH_SHORT).show()
                    val scannedCoords = barcode.split("_")  //Sépare les deux coordonnées (QR code de la forme x_y)
                    val x = scannedCoords[0]
                    Log.d("xcoord", x)  //Debug
                    Log.d("xreel", x.toInt().toString())
                    val y = scannedCoords[1]
                    val currentPoint = Point(x.toInt(), y.toInt())
                    Log.d("currentScan", currentPoint.toString())
                    Log.d("currentPath", navigation[currentStep].toString())
                    val isInPath: Boolean = currentPoint in navigation[currentStep]
                    Log.d("isInPath", isInPath.toString())

                    if (navigation[currentStep] != null) {
                        if (currentPoint in navigation[currentStep]) {
                            placeModel(currentPoint)
                        } else {
                            Toast.makeText(this, "Vous êtes perdus", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            })

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, imageAnalysis)
                isCameraRunning = true
            } catch (e: Exception) {
                // Gestion des erreurs...
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        if (!isCameraRunning) return

        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        cameraProvider.unbindAll()
        isCameraRunning = false
    }

    private fun startCameraWithDelay() {
            startCamera()
            handler.postDelayed({
                stopCamera()
            }, stopDelay) // Arrêter la caméra après 1 seconde
    }

    private fun placeModel(currentPoint: Point) {
        if (scene == true){
            sceneView.removeChild(modelNode) //Suppression du modèle chargé si il y en a un
        }
        // Charger et placer l'objet uniquement lorsqu'on appuie sur le bouton
        val selectedRotation: Float
        val intent = intent
        val navigation = intent.getSerializableExtra("optimalPath") as List<List<Point>> //Récupération de l'itinéraire
        val currentStep = intent.getIntExtra("currentStep",0)
        val posIndex: Int = navigation[currentStep].indexOf(currentPoint)
        val nextPos: Point = navigation[currentStep][posIndex!! + 1] //Position à venir

        if (currentPoint.x < nextPos.x) { //Si la prochaine position est derrière
            selectedRotation = 270f
        } else if (currentPoint.x > nextPos.x) { //Si la prochaine position est devant
            selectedRotation = 90f
        } else if (currentPoint.y < nextPos.y) { //Si la prochaine position est à gauche
            selectedRotation = 0f
        } else { //Si la prochaine position est à droite
            selectedRotation = 180f
        }
        modelNode = ArModelNode(PlacementMode.INSTANT).apply {
            loadModelGlbAsync(
                glbFileLocation = "models/direction_arrow.glb",
                scaleToUnits = 1f,
                centerOrigin = Position(-0.5f),
            ) {
                sceneView.planeRenderer.isVisible = true
            }
        }
        //Rotation de la flèche
        val rotation = Rotation(y = selectedRotation)
        modelNode.worldRotation = rotation

        sceneView.addChild(modelNode)
        scene = true
    }

    override fun onDestroy() {
        super.onDestroy()
        sceneView.destroy()
        cameraExecutor.shutdown()
        barcodeScanner.close()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
    }
}

@ExperimentalGetImage
class BarcodeAnalyzer(private val barcodeListener: (barcode: String) -> Unit) :
    ImageAnalysis.Analyzer {

    private val barcodeScanner: BarcodeScanner = BarcodeScanning.getClient()

    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val inputImage = InputImage.fromMediaImage(
                mediaImage, imageProxy.imageInfo.rotationDegrees
            )

            barcodeScanner.process(inputImage)
                .addOnSuccessListener(OnSuccessListener<List<Barcode>> { barcodes ->
                    for (barcode in barcodes) {
                        barcodeListener(barcode.rawValue ?: "")
                    }
                })
                .addOnFailureListener(OnFailureListener {
                    Log.e(TAG, "Barcode scanning failed", it)
                })
                .addOnCompleteListener {
                    imageProxy.close()
                }
        }
    }

    companion object {
        private const val TAG = "BarcodeAnalyzer"
    }
}
