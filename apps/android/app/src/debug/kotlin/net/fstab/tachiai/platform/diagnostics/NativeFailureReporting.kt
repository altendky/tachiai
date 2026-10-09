package net.fstab.tachiai.platform.diagnostics

import net.fstab.tachiai.platform.media.NativeFailureObserver

internal fun FailureReporter.nativeObserver(): NativeFailureObserver = { stage, error ->
    report(FailureStage.valueOf(stage.name), error)
}
