package com.example.fingerprintsample

import SecuGen.FDxSDKPro.JSGFPLib
import SecuGen.FDxSDKPro.SGAutoOnEventNotifier
import SecuGen.FDxSDKPro.SGDeviceInfoParam
import SecuGen.FDxSDKPro.SGFDxDeviceName
import SecuGen.FDxSDKPro.SGFDxErrorCode
import SecuGen.FDxSDKPro.SGFingerPresentEvent
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Parcelable
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.airbnb.lottie.LottieDrawable
import com.example.fingerprintsample.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity(), SGFingerPresentEvent {


    private val IMAGE_CAPTURE_QUALITY = 100L
    private val IMAGE_CAPTURE_TIMEOUT_MS = 10000L
    private val ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION"

    private var autoOn: SGAutoOnEventNotifier? = null

    private val mUsbReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (ACTION_USB_PERMISSION == action) {
                synchronized(this) {
                    val device =
                        intent.getParcelableExtra<Parcelable>(UsbManager.EXTRA_DEVICE) as UsbDevice?
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            Log.e(
                                Companion.TAG,
                                """
                                USB BroadcastReceiver VID : ${device.vendorId}
                                
                                """.trimIndent()
                            )
                            Log.e(
                                Companion.TAG,
                                """
                                USB BroadcastReceiver PID: ${device.productId}
                                
                                """.trimIndent()
                            )
                        } else Log.e(
                            Companion.TAG,
                            "mUsbReceiver.onReceive() Device is null"
                        )
                    } else Log.e(
                        Companion.TAG,
                        "mUsbReceiver.onReceive() permission denied for device $device"
                    )
                }
            }
        }
    }

    private var mPermissionIntent: PendingIntent? = null
    private var filter: IntentFilter? = null
    private var m_ImgHeight: Int = 0
    private var m_ImgWidth: Int = 0
    private var error: Long = 0
    private lateinit var jsgfplib: JSGFPLib


    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)


        setOnClickListeners()


        //USB Permissions
        mPermissionIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE
        )
        filter = IntentFilter(ACTION_USB_PERMISSION)


        jsgfplib = JSGFPLib(this, getSystemService(Context.USB_SERVICE) as UsbManager);
        error = jsgfplib.Init(SGFDxDeviceName.SG_DEV_AUTO);
        error = jsgfplib.OpenDevice(0)

        enableButtons(isFingerPrintDeviceFound())


    }

    private fun setOnClickListeners() {
        binding.btnScan.setOnClickListener {
            if (isFingerPrintDeviceFound() && hasUsbPermission()) {
                enableButtons(false)
                showPlaceFingerOnDevice()
                autoOn!!.start()

            }
        }

        binding.btnRegister.setOnClickListener {
            enableButtons(false)

            showPlaceFingerOnDevice()

            Handler(Looper.getMainLooper()).postDelayed({
                //showScanningFingerPrint()

                Handler(Looper.getMainLooper()).postDelayed({
                    showSuccessOrFail(true, "Fingerprint successfully registered")
                }, 2000)

            }, 2000)

        }


        binding.btnVerify.setOnClickListener {
            enableButtons(false)

            showPlaceFingerOnDevice()

            Handler(Looper.getMainLooper()).postDelayed({
                //showScanningFingerPrint()

                Handler(Looper.getMainLooper()).postDelayed({
                    showSuccessOrFail(false, "Verification failed")
                }, 2000)

            }, 2000)

        }
    }

    private fun scanFingerPrint() {
        lifecycleScope.launch {
            showScanningFingerPrint()
            val image = ByteArray(m_ImgWidth * m_ImgHeight)

            val job = lifecycleScope.async(Dispatchers.IO) {
                var status = false
                if (jsgfplib.GetImageEx(
                        image,
                        IMAGE_CAPTURE_TIMEOUT_MS,
                        IMAGE_CAPTURE_QUALITY
                    ) == SGFDxErrorCode.SGFDX_ERROR_NONE
                ) {
                    status = true
                }
                status
            }

            if (job.await()) {
                binding.ivFingerPrint.setImageBitmap(toGrayscale(image))
                showSuccessOrFail(true, "Fingerprint scan successful")
            } else {
                showSuccessOrFail(false, "Fingerprint scan failed")
            }
        }
    }

    private fun isFingerPrintDeviceFound(): Boolean {


        val usbDevice: UsbDevice? = jsgfplib.GetUsbDevice()

        if (usbDevice == null) {

            val dlgAlert = AlertDialog.Builder(this)
            dlgAlert.setMessage("SecuGen fingerprint sensor not found!")
            dlgAlert.setTitle("SecuGen Fingerprint SDK")
            dlgAlert.setPositiveButton("OK",
                DialogInterface.OnClickListener { dialog, whichButton ->
                    finish()
                    return@OnClickListener
                }
            )
            dlgAlert.setCancelable(false)
            dlgAlert.create().show()

            return false
        }

        var device_info = SGDeviceInfoParam();
        error = jsgfplib.GetDeviceInfo(device_info)
        if (error === SGFDxErrorCode.SGFDX_ERROR_NONE) {
            m_ImgWidth = device_info.imageWidth
            m_ImgHeight = device_info.imageHeight
        }

        autoOn = SGAutoOnEventNotifier(jsgfplib, this)

        return true
    }

    private fun hasUsbPermission(): Boolean {
        val usbDevice: UsbDevice? = jsgfplib.GetUsbDevice()
        Log.e(TAG, UsbDevice.getDeviceName(0))
        jsgfplib.GetUsbManager().requestPermission(usbDevice, mPermissionIntent)
        return jsgfplib.GetUsbManager().hasPermission(usbDevice)
    }

    private fun enableButtons(b: Boolean) {
        binding.btnRegister.isEnabled = b
        binding.btnVerify.isEnabled = b
        binding.btnScan.isEnabled = b
    }

    private fun showPlaceFingerOnDevice() {
        binding.scanAnim.setAnimation(R.raw.place_finger_on_device_anim)
        binding.scanAnim.playAnimation()
        binding.scanAnim.repeatCount = LottieDrawable.INFINITE
        binding.tvMsg.text = "Place your finger on the device.."


    }

    private fun showScanningFingerPrint() {
        binding.scanAnim.setAnimation(R.raw.fingerprint_scan2)
        binding.scanAnim.playAnimation()
        binding.scanAnim.repeatCount = LottieDrawable.INFINITE
        binding.tvMsg.text = "Scanning Finger Print...."
    }

    private fun showSuccessOrFail(
        successful: Boolean,
        msg: String
    ) {
        if (successful) {
            binding.scanAnim.setAnimation(R.raw.success_amin)
        } else {
            binding.scanAnim.setAnimation(R.raw.failed_anim)
        }

        binding.scanAnim.playAnimation()
        binding.scanAnim.repeatCount = 0
        binding.tvMsg.text = msg

        enableButtons(true)
    }


    fun toGrayscale(mImageBuffer: ByteArray): Bitmap? {
        val Bits = ByteArray(mImageBuffer.size * 4)
        for (i in mImageBuffer.indices) {
            Bits[i * 4 + 2] = mImageBuffer[i]
            Bits[i * 4 + 1] = Bits[i * 4 + 2]
            Bits[i * 4] = Bits[i * 4 + 1] // Invert the source bits
            Bits[i * 4 + 3] = -1 // 0xff, that's the alpha.
        }
        val bmpGrayscale = Bitmap.createBitmap(m_ImgWidth, m_ImgHeight, Bitmap.Config.ARGB_8888)
        bmpGrayscale.copyPixelsFromBuffer(ByteBuffer.wrap(Bits))
        return bmpGrayscale
    }


    override fun SGFingerPresentCallback() {
        autoOn!!.stop()
        fingerDetectedHandler.sendMessage(Message())
    }


    var fingerDetectedHandler: Handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            scanFingerPrint()
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}