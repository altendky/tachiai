package net.fstab.tachiai.platform.media;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.dash.manifest.DashManifestParser;
import androidx.media3.exoplayer.dash.manifest.RangedUri;
import androidx.media3.exoplayer.dash.manifest.SegmentBase;
import androidx.media3.exoplayer.dash.manifest.UrlTemplate;
import java.util.List;

/**
 * Media3 passes a null initialization or timeline to buildSegmentTemplate for
 * templates without them, but does not annotate those parameters, so a Kotlin
 * override would fail its own null checks. This shim re-exposes the hook with
 * explicitly nullable parameters (#568).
 */
@UnstableApi
abstract class NullableTemplateDashManifestParser extends DashManifestParser {
  @Override
  protected final SegmentBase.SegmentTemplate buildSegmentTemplate(
      RangedUri initialization, long timescale, long presentationTimeOffset, long startNumber,
      long endNumber, long duration, List<SegmentBase.SegmentTimelineElement> timeline,
      long availabilityTimeOffsetUs, @Nullable UrlTemplate initializationTemplate,
      @Nullable UrlTemplate mediaTemplate, long timeShiftBufferDepthMs, long periodStartUnixTimeMs) {
    return buildTemplate(initialization, timescale, presentationTimeOffset, startNumber, endNumber,
        duration, timeline, availabilityTimeOffsetUs, initializationTemplate, mediaTemplate,
        timeShiftBufferDepthMs, periodStartUnixTimeMs);
  }

  /** Override point; the default builds the template unchanged. */
  protected SegmentBase.SegmentTemplate buildTemplate(
      @Nullable RangedUri initialization, long timescale, long presentationTimeOffset,
      long startNumber, long endNumber, long duration,
      @Nullable List<SegmentBase.SegmentTimelineElement> timeline, long availabilityTimeOffsetUs,
      @Nullable UrlTemplate initializationTemplate, @Nullable UrlTemplate mediaTemplate,
      long timeShiftBufferDepthMs, long periodStartUnixTimeMs) {
    return defaultTemplate(initialization, timescale, presentationTimeOffset, startNumber,
        endNumber, duration, timeline, availabilityTimeOffsetUs, initializationTemplate,
        mediaTemplate, timeShiftBufferDepthMs, periodStartUnixTimeMs);
  }

  protected final SegmentBase.SegmentTemplate defaultTemplate(
      @Nullable RangedUri initialization, long timescale, long presentationTimeOffset,
      long startNumber, long endNumber, long duration,
      @Nullable List<SegmentBase.SegmentTimelineElement> timeline, long availabilityTimeOffsetUs,
      @Nullable UrlTemplate initializationTemplate, @Nullable UrlTemplate mediaTemplate,
      long timeShiftBufferDepthMs, long periodStartUnixTimeMs) {
    return super.buildSegmentTemplate(initialization, timescale, presentationTimeOffset,
        startNumber, endNumber, duration, timeline, availabilityTimeOffsetUs,
        initializationTemplate, mediaTemplate, timeShiftBufferDepthMs, periodStartUnixTimeMs);
  }
}
