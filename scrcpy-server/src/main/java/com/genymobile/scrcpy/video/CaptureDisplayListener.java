package com.genymobile.scrcpy.video;

import com.genymobile.scrcpy.control.PositionMapper;

/** Receives the current primary-display input mapping after capture setup or reset. */
public interface CaptureDisplayListener {
    void onCaptureDisplay(int displayId, PositionMapper positionMapper);
}
