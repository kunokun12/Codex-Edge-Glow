package com.codexedge.glow.fastpathv1

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ReceiverCallNotAllowedException
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class AndroidMessengerObserverTransportV1Test {
    private val context get() = RuntimeEnvironment.getApplication()
    private val request = ObserverIpcRequestMessageV1(
        "tessera.observer-ipc/1", "A".repeat(48),
        "00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002",
        "org.example.safe", 9, 2500, 4096, 100, "12345678-1234-1234-1234-123456789abc"
    )
    private val expectedComponent = ComponentName("org.tessera.observer", "org.tessera.observer.ObserverIpcService")

    @Test fun falseBindReturnIsRejectedAndDoesNotUnbind() {
        installVisibleService()
        val binder = FakeBinder { _, _, _ -> false }
        val result = AndroidMessengerObserverTransportV1(context, binder).exchange(request, 100)
        assertEquals(ObserverIpcTransportResultV1.Failure("observer-bind-rejected"), result)
        assertEquals(0, binder.unbindCount)
        assertEquals(expectedComponent, binder.intent?.component)
        assertEquals(Context.BIND_AUTO_CREATE, binder.flags)
    }

    @Test fun broadcastReceiverRestrictedContextIsReplacedWithApplicationContextForBinding() {
        installVisibleService()
        val appContext = RecordingApplicationContext(context)
        val receiverContext = RestrictedReceiverContext(appContext)
        val result = AndroidMessengerObserverTransportV1(receiverContext).exchange(request, 100)

        assertEquals(ObserverIpcTransportResultV1.Failure("observer-bind-rejected"), result)
        assertEquals("the receiver wrapper must never perform the bind", 0, receiverContext.bindAttempts)
        assertEquals("the application context performs one explicit bind", 1, appContext.bindAttempts)
        assertEquals(expectedComponent, appContext.lastIntent?.component)
        assertEquals(Context.BIND_AUTO_CREATE, appContext.lastFlags)
        assertEquals("receiver-context", ObserverBindFailureV1.classify(
            ReceiverCallNotAllowedException("private framework message")).category)
    }

    @Test fun securityAndLifecycleExceptionsRemainDistinctAndSanitized() {
        installVisibleService()
        val security = throwingBinder(SecurityException("private path and request contents"))
        assertEquals(ObserverIpcTransportResultV1.Failure("observer-permission-denied"),
            AndroidMessengerObserverTransportV1(context, security).exchange(request, 100))

        val lifecycle = throwingBinder(IllegalStateException("private lifecycle detail"))
        assertEquals(ObserverIpcTransportResultV1.Failure("observer-bind-lifecycle"),
            AndroidMessengerObserverTransportV1(context, lifecycle).exchange(request, 100))

        val invalidIntent = throwingBinder(IllegalArgumentException("private intent detail"))
        assertEquals(ObserverIpcTransportResultV1.Failure("observer-bind-invalid-intent"),
            AndroidMessengerObserverTransportV1(context, invalidIntent).exchange(request, 100))

        assertEquals("lifecycle", ObserverBindFailureV1.classify(IllegalStateException("x")).category)
        assertEquals("IllegalStateException", ObserverBindFailureV1.classify(IllegalStateException("x")).exceptionClass)
        assertFalse(ObserverBindFailureV1.classify(IllegalStateException("secret/path"))
            .toString().contains("secret/path"))
    }

    @Test fun timeoutAndInterruptionUnbindExactlyOnceAndIgnoreLateCallback() {
        installVisibleService()
        val bindEntered = CountDownLatch(1)
        val connectionRef = AtomicReference<ServiceConnection>()
        val binder = FakeBinder { _, connection, _ ->
            connectionRef.set(connection)
            bindEntered.countDown()
            true
        }
        val returned = AtomicReference<ObserverIpcTransportResultV1>()
        val worker = Thread {
            returned.set(AndroidMessengerObserverTransportV1(context, binder).exchange(request, 5000))
        }
        worker.start()
        assertTrue(bindEntered.await(1, TimeUnit.SECONDS))
        worker.interrupt()
        worker.join(1000)
        assertFalse("interrupted exchange must finish", worker.isAlive)
        assertEquals(ObserverIpcTransportResultV1.Failure("observer-wait-interrupted"), returned.get())
        assertEquals(1, binder.unbindCount)

        val (late, lateThread) = testMessenger()
        try {
            connectionRef.get().onServiceConnected(expectedComponent, late.binder)
            assertEquals("late callbacks cannot cause another bind or unbind", 1, binder.unbindCount)
        } finally {
            lateThread.quitSafely()
        }
    }

    @Test fun serviceDisconnectAndNullBindingAreLifecycleRefusals() {
        installVisibleService()
        val disconnected = FakeBinder { _, connection, _ ->
            connection.onServiceDisconnected(expectedComponent)
            true
        }
        assertEquals(ObserverIpcTransportResultV1.Failure("observer-service-disconnected"),
            AndroidMessengerObserverTransportV1(context, disconnected).exchange(request, 100))
        assertEquals(1, disconnected.unbindCount)

        val nullBinding = FakeBinder { _, connection, _ ->
            connection.onNullBinding(expectedComponent)
            true
        }
        assertEquals(ObserverIpcTransportResultV1.Failure("observer-null-binding"),
            AndroidMessengerObserverTransportV1(context, nullBinding).exchange(request, 100))
        assertEquals(1, nullBinding.unbindCount)
    }

    @Test fun successfulExplicitMessengerConnectionReturnsCorrelatedReply() {
        installVisibleService()
        val messengerAndThread = testMessenger()
        val messenger = messengerAndThread.first
        val thread = messengerAndThread.second
        val binder = FakeBinder { intent, connection, flags ->
            assertEquals(expectedComponent, intent.component)
            assertEquals(Context.BIND_AUTO_CREATE, flags)
            connection.onServiceConnected(expectedComponent, messenger.binder)
            true
        }
        try {
            val result = AndroidMessengerObserverTransportV1(context, binder).exchange(request, 1000)
            assertTrue("expected authenticated Messenger reply: $result", result is ObserverIpcTransportResultV1.Reply)
            val reply = (result as ObserverIpcTransportResultV1.Reply).value.reply
            assertEquals(request.requestId, reply.requestId)
            assertEquals(request.executionId, reply.executionId)
            assertEquals(request.challenge, reply.challenge)
            assertEquals("snapshot-package-mismatch", reply.reasonCode)
            assertEquals(1, binder.unbindCount)
        } finally {
            thread.quitSafely()
        }
    }

    @Test fun thrownBindingFailureGetsStableReasonCategory() {
        assertEquals("observer-bind-runtime", ObserverBindFailureV1.classify(Exception("sensitive detail")).reasonCode)
        assertEquals("observer-bind-invalid-intent", ObserverBindFailureV1.classify(IllegalArgumentException()).reasonCode)
    }

    private fun installVisibleService() {
        shadowOf(context.packageManager).addOrUpdateService(ServiceInfo().apply {
            packageName = expectedComponent.packageName
            name = expectedComponent.className
            exported = true
            permission = "org.tessera.observer.permission.READ_EVIDENCE"
        })
    }

    private fun throwingBinder(failure: Exception) = FakeBinder { _, _, _ -> throw failure }

    private class RecordingApplicationContext(base: Context) : ContextWrapper(base) {
        var bindAttempts = 0
        var lastIntent: Intent? = null
        var lastFlags = -1
        override fun getApplicationContext(): Context = this
        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
            bindAttempts++
            lastIntent = service
            lastFlags = flags
            return false
        }
    }

    private class RestrictedReceiverContext(base: Context) : ContextWrapper(base) {
        var bindAttempts = 0
        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
            bindAttempts++
            throw ReceiverCallNotAllowedException("private framework message")
        }
    }

    private data class FakeBinder(
        var intent: Intent? = null,
        var flags: Int = -1,
        var unbindCount: Int = 0,
        val bindBlock: (Intent, ServiceConnection, Int) -> Boolean
    ) : ObserverServiceBinderV1 {
        override fun bind(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            this.intent = intent
            this.flags = flags
            return bindBlock(intent, connection, flags)
        }
        override fun unbind(connection: ServiceConnection) { unbindCount++ }
    }

    private fun testMessenger(): Pair<Messenger, HandlerThread> {
        val thread = HandlerThread("test-observer-ipc-service").apply { start() }
        val messenger = Messenger(Handler(thread.looper) { incoming ->
            val requestData = incoming.data
            val response = Bundle().apply {
                putString("protocol", "tessera.observer-ipc/1")
                putString("outcome", "REFUSED")
                putString("reasonCode", "snapshot-package-mismatch")
                putString("requestId", requestData.getString("requestId"))
                putString("executionId", requestData.getString("executionId"))
                putString("challenge", requestData.getString("challenge"))
                putString("bootTimebaseId", requestData.getString("bootTimebaseId"))
                putString("packageName", null)
                putInt("windowId", -1)
                putLong("capturedElapsedRealtimeMs", -1)
                putLong("observerElapsedRealtimeMs", 200)
                putString("evidenceDigest", null)
                putString("snapshotJson", null)
                putString("observerPackage", "org.tessera.observer")
                putString("observerArtifactIdentity", "org.tessera.observer/4/test")
                putString("observerVersionName", "0.4.0-test")
                putLong("observerVersionCode", 4)
                putString("observerSignerSha256", "a".repeat(64))
                putString("observerArtifactSha256", "b".repeat(64))
                putString("observerSourceCommit", "c".repeat(40))
                putBoolean("complete", false)
                putBoolean("verified", false)
            }
            incoming.replyTo?.send(Message.obtain(null, 1).apply { data = response })
            true
        })
        return messenger to thread
    }
}
