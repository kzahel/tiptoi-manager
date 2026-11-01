package io.github.kzahel.tiptoimanager

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract

data class UsbConnectionState(val attached: Boolean, val drives: List<StorageVolume>)

/** Observe mounting without opening a USB interface or claiming the system's driver. */
object UsbConnection {
    fun observe(context: Context): UsbConnectionState {
        val devices = context.getSystemService(UsbManager::class.java)?.deviceList?.values.orEmpty()
        val attached = devices.any { device ->
            device.deviceClass == UsbConstants.USB_CLASS_MASS_STORAGE ||
                (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE }
        }
        val drives = context.getSystemService(StorageManager::class.java)?.storageVolumes.orEmpty()
            .filter { !it.isPrimary && it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
        // Android exposes removable volumes separately from USB devices. With several
        // volumes, let the user choose: a removable SD card is not necessarily this pen.
        return UsbConnectionState(attached, drives)
    }

    fun folderIntent(drive: StorageVolume?, previous: Uri?): Intent {
        val intent = if (drive != null && Build.VERSION.SDK_INT >= 29) drive.createOpenDocumentTreeIntent()
            else Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                previous?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
            }
        return intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
    }
}
