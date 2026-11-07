package com.famage.skydrop.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferStateTest {

    @Test
    fun testTransferState_UploadingProgress() {
        val state = TransferState.Uploading(
            currentFileName = "presentation.pdf",
            bytesTransferred = 5242880L,
            totalBytes = 10485760L,
            progressPercent = 50
        )

        assertEquals("presentation.pdf", state.currentFileName)
        assertEquals(5242880L, state.bytesTransferred)
        assertEquals(10485760L, state.totalBytes)
        assertEquals(50, state.progressPercent)
    }

    @Test
    fun testTransferState_SuccessAndError() {
        val success = TransferState.Success("Upload complete")
        val error = TransferState.Error("Network unreachable")

        assertEquals("Upload complete", success.message)
        assertEquals("Network unreachable", error.errorMessage)
        assertEquals(TransferState.Idle, TransferState.Idle)
        assertEquals(TransferState.Preparing, TransferState.Preparing)
    }
}
