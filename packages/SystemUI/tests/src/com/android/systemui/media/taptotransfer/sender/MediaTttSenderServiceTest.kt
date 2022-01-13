package com.android.systemui.media.taptotransfer.sender

import android.media.MediaRoute2Info
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.shared.mediattt.IDeviceSenderCallback
import com.android.systemui.util.mockito.any
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
class MediaTttSenderServiceTest : SysuiTestCase() {

    private lateinit var service: MediaTttSenderService
    private lateinit var callback: IDeviceSenderCallback

    @Mock
    private lateinit var controller: MediaTttChipControllerSender

    private val mediaInfo = MediaRoute2Info.Builder("id", "Test Name")
        .addFeature("feature")
        .build()

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        service = MediaTttSenderService(context, controller)
        callback = IDeviceSenderCallback.Stub.asInterface(service.onBind(null))
    }

    @Test
    fun closeToReceiverToStartCast_controllerTriggeredWithMoveCloserToStartCastState() {
        callback.closeToReceiverToStartCast(mediaInfo)

        verify(controller).displayChip(any<MoveCloserToStartCast>())
    }
}
