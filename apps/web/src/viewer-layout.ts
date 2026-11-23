import type { Rect } from './pair';

// NativePairViewer logical-unit formulas. The six 48-unit toolbar targets and
// 8-unit side padding can take precedence over 45% at the 640-unit threshold.
export function controlDockWidth(width: number, landscape: boolean, panel: boolean): number {
  return landscape && panel && width >= 640 ? Math.min(360, Math.max(Math.floor(width * .45), 6 * 48 + 16)) : width;
}

// Native panels that keep their own space (Timing, Quality); Audio and Status float over the video.
export const reservingPanel = (panel: string | undefined) => panel === 'timing' || panel === 'quality';

export interface ViewerLayout {
  stage: Rect; dock: Rect; bodyCap: number; sideDock: boolean;
  floatingRight: number; floatingBottom: number;
}
// The basic controls overlay the video (#656): only an open reserving panel resizes
// the stage, with Fit on in landscape and always in portrait. Hidden controls reserve nothing.
export function viewerLayout(width: number, height: number, landscape: boolean, panel: boolean,
  controls: boolean, fit: boolean, toolbarHeight: number, bodyHeight: number, reserves = false): ViewerLayout {
  const sideDock = landscape && panel && width >= 640;
  const dockWidth = controlDockWidth(width, landscape, panel);
  const bodyCap = Math.max(0, Math.min(Math.floor(height * .6), height - toolbarHeight - 8));
  const dockHeight = Math.min(height, toolbarHeight + 8 + (panel ? Math.min(bodyHeight, bodyCap) : 0));
  const reserving = controls && panel && reserves;
  const reserveW = reserving && sideDock && fit ? dockWidth : 0;
  const reserveH = reserving && !sideDock && (fit || !landscape) ? dockHeight : 0;
  const stage = { left: 0, top: 0, width: Math.max(0, width - reserveW), height: Math.max(0, height - reserveH) };
  return { stage, dock: { left: width - dockWidth, top: height - dockHeight, width: dockWidth, height: dockHeight }, bodyCap, sideDock,
    floatingRight: controls && sideDock ? Math.max(0, Math.min(stage.width, width - dockWidth)) : stage.width,
    floatingBottom: controls && !sideDock && landscape ? Math.max(0, Math.min(stage.height, height - dockHeight)) : stage.height };
}
