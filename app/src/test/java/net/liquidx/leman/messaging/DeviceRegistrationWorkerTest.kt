package net.liquidx.leman.messaging

import androidx.work.ListenableWorker
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceRegistrationWorkerTest {
    @Test fun done_isSuccess() =
        assertTrue(RegistrationResult.of(DeviceRegistrar.Outcome.DONE) is ListenableWorker.Result.Success)
    @Test fun gaveUp_isSuccess() =
        assertTrue(RegistrationResult.of(DeviceRegistrar.Outcome.GAVE_UP) is ListenableWorker.Result.Success)
    @Test fun retryLater_isRetry() =
        assertTrue(RegistrationResult.of(DeviceRegistrar.Outcome.RETRY_LATER) is ListenableWorker.Result.Retry)
}
