package com.genymobile.scrcpy.control;

import com.genymobile.scrcpy.model.Point;
import com.genymobile.scrcpy.model.Position;
import com.genymobile.scrcpy.model.Size;

/** Maps the constrained video coordinates back to the current primary-display coordinates. */
public final class PositionMapper {

    private final Size videoSize;
    private final Size targetSize;

    private PositionMapper(Size videoSize, Size targetSize) {
        this.videoSize = videoSize;
        this.targetSize = targetSize;
    }

    public static PositionMapper create(Size videoSize, Size targetSize) {
        return new PositionMapper(videoSize, targetSize);
    }

    public Size getVideoSize() {
        return videoSize;
    }

    public Point map(Position position) {
        if (!videoSize.equals(position.getScreenSize())) {
            // Rotation/fold reset changed dimensions after this event was generated.
            return null;
        }
        Point point = position.getPoint();
        if (videoSize.equals(targetSize)) {
            return point;
        }
        int x = (int) ((long) point.getX() * targetSize.getWidth() / videoSize.getWidth());
        int y = (int) ((long) point.getY() * targetSize.getHeight() / videoSize.getHeight());
        return new Point(x, y);
    }
}
