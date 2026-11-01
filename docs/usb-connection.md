# Guided USB connection

My pens, pen details and Help open the same three-step connection flow:

1. Connect the pen directly with a USB data cable/adapter. The app observes
   Android's USB mass-storage devices and waits for a writable mounted volume.
2. Allow folder access. On Android 10+ the picker initially shows the selected
   removable volume's root. Confirm that root in Android's picker and consent
   dialog. Android 8–9 use the ordinary folder picker. Multiple removable
   volumes require a drive choice because Android's volume list does not
   identify which volume belongs to a particular USB device.
3. Read and validate the pen identity/inventory, then show its local name and
   a button to view its books. An arbitrary folder is rejected with a retry.

Known pens can skip the access step. Android retains the granted URI through
`takePersistableUriPermission`; the app saves the selected tree and profile
trees. Process startup loads those and all existing read/write grants, then
attempts discovery. Foreground connection checks retry after mounting. Profile
recognition uses the pen's identity, not a volume label or the selected folder
alone. Connecting another pen does not imply any file installation/removal.

Android's persistent grants are intended to survive device reboot. The USB
drive must finish mounting and user storage must be unlocked before it is
readable. Revocation, uninstall, a reformatted drive or a changed provider URI
can require a new grant. A remembered grant does not prove a drive is connected.

Picker cancellation returns to the wizard. Manual folder selection and cable
help remain available if device detection does not expose the expected drive.
The app never opens a raw USB interface, takes over the system's storage driver,
or requests all-files access. The picker still controls the actual selected
location; the suggested volume does not bypass consent or identity validation.

## Read-only diagnostics

On an explicitly selected authorized device, with the development and test APKs
installed:

```
adb -s SERIAL shell am instrument -w -r \
  -e connectionReadOnly true -e requireConnected true \
  io.github.kzahel.tiptoimanager.debug.test/io.github.kzahel.tiptoimanager.UsbSmokeInstrumentation
```

Force-stop the app before running to check startup rediscovery. The diagnostic
checks a retained read/write grant, automatic recognition and a fresh readable
identity/inventory. It never writes pen files or prints the pen serial. Omit
`requireConnected` to inspect retained grants while disconnected; that result
does not establish successful reconnection. See [acceptance](acceptance.md) for
actual physical versus synthetic-fixture evidence.

References: [Android persistent document access](https://developer.android.com/training/data-storage/shared/documents-files#persist-permissions),
[volume-specific picker](https://developer.android.com/reference/android/os/storage/StorageVolume#createOpenDocumentTreeIntent()),
[USB device discovery](https://developer.android.com/develop/connectivity/usb/host#discover).
