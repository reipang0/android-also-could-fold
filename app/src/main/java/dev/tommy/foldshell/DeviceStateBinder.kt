package dev.tommy.foldshell

import android.os.Binder
import android.os.IBinder
import android.os.Parcel

// Raw binder access to IDeviceStateManager. Verified on SM-F976N:
// transaction 3 = requestState(token, state, flags) works from the top app
// without CONTROL_DEVICE_STATE; transaction 4 = cancelStateRequest(token).
// Base-state override (codes 5/6) is system/shell only.
object DeviceStateBinder {
    private var service: IBinder? = null
    private val token = Binder()

    private const val TX_REQUEST_STATE = 3
    private const val TX_CANCEL_STATE = 4

    const val CONCURRENT_INNER = 4
    const val CONCURRENT_OUTER = 5

    private fun binder(): IBinder? {
        service?.takeIf { it.isBinderAlive }?.let { return it }
        service = try {
            Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "device_state") as? IBinder
        } catch (t: Throwable) { null }
        return service
    }

    fun request(state: Int, flags: Int = 0): String {
        val b = binder() ?: return "no binder"
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(b.interfaceDescriptor ?: "android.hardware.devicestate.IDeviceStateManager")
            data.writeStrongBinder(token)
            data.writeInt(state)
            data.writeInt(flags)
            val ok = b.transact(TX_REQUEST_STATE, data, reply, 0)
            reply.readException()
            "ok=$ok"
        } catch (t: Throwable) { "fail ${t.cause?.message ?: t.message}" }
        finally { data.recycle(); reply.recycle() }
    }

    fun cancel(): String {
        val b = binder() ?: return "no binder"
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(b.interfaceDescriptor ?: "android.hardware.devicestate.IDeviceStateManager")
            data.writeStrongBinder(token)
            val ok = b.transact(TX_CANCEL_STATE, data, reply, 0)
            reply.readException()
            "ok=$ok"
        } catch (t: Throwable) { "fail ${t.cause?.message ?: t.message}" }
        finally { data.recycle(); reply.recycle() }
    }
}
