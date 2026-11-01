import {test, expect, chromium, type Page} from '@playwright/test';

async function arrive(page: Page, baseURL: string) {
  await page.goto(new URL('./?history-prior=first', baseURL).href);
  await page.goto(new URL('./?history-prior=second', baseURL).href);
  await page.goto(new URL('pair.html', baseURL).href);
  await expect(page.getByRole('status')).toHaveText('Timing core ready');
}
async function watch(page: Page) {
  await page.locator('#source-A-a-dash').check();
  await page.locator('#source-B-b-hls').check();
  await expect(page.locator('#watch')).toBeEnabled();
  await page.locator('#watch').click();
  await playing(page);
}
async function playing(page: Page) {
  await expect.poll(() => page.evaluate(() => {
    const state = window.pair.observe();
    return ['A', 'B'].every(side => {
      const value = state.feeds[side as 'A' | 'B'];
      return value?.state === 'ready' && !value.paused && (value.positionMs ?? 0) > 200;
    });
  })).toBe(true);
}
async function sources(page: Page) {
  await expect(page.locator('#picker')).toBeVisible();
  await expect(page.locator('video')).toHaveCount(0);
  await expect(page.locator('#watch')).toBeEnabled();
}
async function retained(page: Page) {
  expect(await page.evaluate(() => {
    const prior = (window as any).historyOwnedPlayers;
    return prior.A === window.pair.slots.A.current && prior.B === window.pair.slots.B.current;
  })).toBe(true);
}
async function remember(page: Page) {
  await page.evaluate(() => {(window as any).historyOwnedPlayers = {
    A: window.pair.slots.A.current, B: window.pair.slots.B.current,
  };});
}
async function layer(page: Page, kind: string, panel?: string) {
  await expect.poll(() => page.evaluate(() => {
    const value = history.state?.__tachiaiFixtureHistory;
    return {kind: value?.kind, ...(value?.panel ? {panel: value.panel} : {})};
  })).toEqual({kind, ...(panel ? {panel} : {})});
}

test('actual browser Back unwinds the common layers, retires once and leaves to the immediate prior page', async ({page, baseURL}) => {
  await arrive(page, baseURL!); await watch(page); await remember(page);
  await page.locator('#audio').click(); await page.locator('#more').click();
  await layer(page, 'menu', 'audio');
  await page.goBack();
  await expect(page.locator('#more-menu')).toBeHidden();
  await expect(page.locator('#audio-panel')).toBeVisible(); await retained(page);
  await page.goBack();
  await expect(page.locator('#panel-body')).toBeHidden();
  await expect(page.locator('#dock')).toBeVisible(); await retained(page);
  await page.goBack();
  await expect(page.locator('#dock')).toBeHidden(); await retained(page); await playing(page);
  await page.goBack(); await sources(page);
  await page.goBack(); await expect(page).toHaveURL(/history-prior=second$/);
  await page.goBack(); await expect(page).toHaveURL(/history-prior=first$/);
});

test('menu to another panel has only one panel Back layer and header/Escape share browser history', async ({page, baseURL}) => {
  await arrive(page, baseURL!); await watch(page); await remember(page);
  await page.locator('#audio').click(); await page.locator('#more').click();
  await page.locator('#quality').click();
  await expect(page.locator('#quality-panel')).toBeVisible(); await layer(page, 'panel', 'quality');
  await page.goBack();
  await expect(page.locator('#panel-body')).toBeHidden();
  await expect(page.locator('#audio-panel')).toBeHidden(); await retained(page);
  await page.locator('#timing').click(); await layer(page, 'panel', 'timing');
  await page.keyboard.press('Escape'); await layer(page, 'controls');
  await page.locator('#back').click(); await layer(page, 'hidden'); await retained(page);
  await page.goBack(); await sources(page);
});

test('automatic Hide and explicit reveal keep one current controls layer', async ({page, baseURL}) => {
  await arrive(page, baseURL!); await watch(page); await remember(page);
  await expect(page.locator('#dock')).toBeHidden({timeout: 6500}); await layer(page, 'hidden');
  await page.locator('#pane-A').click({position: {x: 20, y: 50}});
  await expect(page.locator('#dock')).toBeVisible(); await layer(page, 'controls');
  await page.goBack(); await expect(page.locator('#dock')).toBeHidden(); await retained(page);
  await page.goBack(); await sources(page);
  await page.goBack(); await expect(page).toHaveURL(/history-prior=second$/);
});

test('Forward through every retired viewer entry stays Sources and Back skips only its owned retired chain', async ({page, baseURL}) => {
  await arrive(page, baseURL!); await watch(page);
  await page.locator('#audio').click(); await page.locator('#more').click(); await layer(page, 'menu', 'audio');
  await page.locator('#sources').click(); await sources(page); await layer(page, 'sources');
  for (let i = 0; i < 4; i++) {
    await page.goForward(); await sources(page); await layer(page, 'sources');
  }
  await page.goBack(); await expect(page).toHaveURL(/history-prior=second$/);
  await page.goBack(); await expect(page).toHaveURL(/history-prior=first$/);
});

test('rapid Back/Forward cannot admit Watch while a retired player is still disposing', async ({page, baseURL}) => {
  await arrive(page, baseURL!); await watch(page);
  await page.evaluate(() => {
    const player = window.pair.slots.A.current!, ordinary = player.dispose.bind(player);
    let release!: () => void;
    const gate = new Promise<void>(resolve => {release = resolve;});
    player.dispose = async () => {await gate; await ordinary();};
    (window as any).releaseHistoryDisposal = release;
  });
  await page.locator('#sources').click();
  await expect(page.locator('#picker')).toBeVisible(); await expect(page.locator('#watch')).toBeDisabled();
  await layer(page, 'sources');
  await page.goForward(); await page.goForward();
  expect(await page.evaluate(() => ({mode: window.pair.observe().mode,
    owned: !!window.pair.slots.A.current || !!window.pair.slots.B.current}))).toEqual({mode: 'sources', owned: false});
  await expect(page.locator('#watch')).toBeDisabled();
  await page.evaluate(() => (window as any).releaseHistoryDisposal());
  await sources(page);
  await page.locator('#watch').click(); await playing(page); await layer(page, 'controls');
  await page.goBack(); await layer(page, 'hidden');
  await page.goForward(); await layer(page, 'controls'); await playing(page);
});

test('a late application rewind cannot stop a newer explicitly admitted Watch', async ({page, baseURL}) => {
  await arrive(page, baseURL!); await watch(page);
  await page.evaluate(async () => {
    const ordinary = history.go.bind(history), requests: number[] = [];
    history.go = delta => {requests.push(delta ?? 0);};
    await window.pair.stop();
    (window as any).newExplicitWatch = window.pair.watch();
    (window as any).releaseHistoryRewind = () => {
      history.go = ordinary;
      if (requests.length !== 1) throw Error('Expected one owned rewind');
      ordinary(requests[0]);
    };
  });
  await page.evaluate(() => (window as any).releaseHistoryRewind());
  await expect.poll(() => page.evaluate(() => window.pair.observe().mode)).toBe('viewer');
  await playing(page); await layer(page, 'controls');
  await page.goBack(); await layer(page, 'hidden'); await playing(page);
});

test('a current rewind acknowledges its anchor before an older suspended rewind tombstone', async ({page, baseURL}) => {
  await arrive(page, baseURL!); await watch(page);
  await page.evaluate(async () => {
    const ordinary = history.go.bind(history), requests: number[] = [];
    history.go = delta => {requests.push(delta ?? 0);};
    await window.pair.stop();
    window.dispatchEvent(new Event('pagehide'));
    window.dispatchEvent(new Event('pageshow'));
    (window as any).releaseCurrentRewind = () => {
      history.go = ordinary;
      if (requests.length !== 2) throw Error('Expected retired and current owned rewinds');
      // The old request is deliberately undelivered after retirement. Deliver
      // the current request through actual browser traversal to the same anchor.
      ordinary(requests[1]);
    };
  });
  await expect(page.locator('#watch')).toBeEnabled();
  await page.locator('#watch').click(); await playing(page);
  await page.evaluate(() => (window as any).releaseCurrentRewind());
  await layer(page, 'controls');
  await expect(page.locator('#watch')).toBeEnabled();
  await page.goBack(); await layer(page, 'hidden');
  await page.goBack(); await sources(page);
});

test('a retired rewind delivered after resume stays Sources and cannot suppress the next actual Back', async ({page, baseURL}) => {
  await arrive(page, baseURL!); await watch(page);
  await page.evaluate(async () => {
    const ordinary = history.go.bind(history), requests: number[] = [];
    history.go = delta => {requests.push(delta ?? 0);};
    await window.pair.stop();
    window.dispatchEvent(new Event('pagehide'));
    window.dispatchEvent(new Event('pageshow'));
    (window as any).releaseRetiredHistoryRewind = () => {
      history.go = ordinary;
      if (requests.length !== 1) throw Error('Expected one retired owned rewind');
      ordinary(requests[0]);
    };
  });
  await expect(page.locator('#watch')).toBeEnabled();
  await page.evaluate(() => (window as any).releaseRetiredHistoryRewind());
  await layer(page, 'sources');
  await expect.poll(() => page.evaluate(() => history.state?.__tachiaiFixtureHistory?.position)).toBe(0);
  await sources(page); await expect(page).toHaveURL(/\/pair\.html$/);
  await page.goBack(); await expect(page).toHaveURL(/history-prior=second$/);
});

test('queued Sources Back cannot carry into a fresh Watch after direct-entry rewind', async ({page, baseURL}) => {
  await page.goto(new URL('pair.html', baseURL!).href);
  await expect(page.getByRole('status')).toHaveText('Timing core ready'); await watch(page);
  await page.evaluate(() => {
    const ordinary = history.go.bind(history), requests: number[] = [];
    history.go = delta => {requests.push(delta ?? 0);};
    (window as any).releaseQueuedSourcesRewind = () => {
      history.go = ordinary;
      if (requests.length !== 1) throw Error('Expected one Sources rewind');
      ordinary(requests[0]);
    };
  });
  await page.locator('#sources').click();
  await expect(page.locator('#picker')).toBeVisible();
  await page.locator('#back').click(); await page.locator('#back').click(); await page.locator('#back').click();
  await expect.poll(() => page.evaluate(() => {
    const state = window.pair.observe();
    return !state.stopping && state.qualityStore === 'ready';
  })).toBe(true);
  // A new explicit model Watch is admitted before the old navigation request
  // can drain. Back issued while Sources would otherwise legitimately exit.
  await page.evaluate(() => {(window as any).queuedReplacementWatch = window.pair.watch();});
  await playing(page); await remember(page);
  await page.evaluate(() => (window as any).releaseQueuedSourcesRewind());
  await layer(page, 'controls'); await retained(page);
  await page.waitForTimeout(150);
  await layer(page, 'controls'); await playing(page);
});

test('panel branches keep the live tab registry bounded to the reachable owned chain', async ({page, baseURL}) => {
  await arrive(page, baseURL!); await watch(page);
  for (let index = 0; index < 10; index++) {
    await page.locator('#audio').click(); await layer(page, 'panel', 'audio');
    await page.locator('#audio').click(); await layer(page, 'controls');
  }
  expect(await page.evaluate(() => JSON.parse(sessionStorage.getItem('tachiai-fixture-history-v1')!).entries.length)).toBeLessThanOrEqual(5);
  await page.locator('#audio').click(); await page.locator('#more').click(); await layer(page, 'menu', 'audio');
  await page.locator('#sources').click(); await sources(page);
  expect(await page.evaluate(() => JSON.parse(sessionStorage.getItem('tachiai-fixture-history-v1')!).entries.length)).toBe(5);
  await page.goForward(); await sources(page);
  await page.goForward(); await sources(page);
});

test('reload of a viewer entry shows Sources and never replays its recorded epoch', async ({page, baseURL}) => {
  await arrive(page, baseURL!); await watch(page); await page.locator('#audio').click();
  await layer(page, 'panel', 'audio'); await page.reload();
  await expect(page.getByRole('status')).toHaveText('Timing core ready');
  await expect(page.locator('#picker')).toBeVisible(); await expect(page.locator('video')).toHaveCount(0);
  expect(await page.evaluate(() => window.pair.observe().mode)).toBe('sources');
  await page.goBack(); await expect(page).toHaveURL(/history-prior=second$/);
});

test('unavailable tab storage fails closed on reload instead of restoring playback', async ({page, baseURL}) => {
  await page.addInitScript(() => {
    if (location.pathname.endsWith('/pair.html')) Object.defineProperty(window, 'sessionStorage', {
      get() {throw new DOMException('Owned storage refusal', 'SecurityError');},
    });
  });
  await arrive(page, baseURL!); await watch(page); await page.reload();
  await expect(page.getByRole('status')).toHaveText('Timing core ready');
  await expect(page.locator('#picker')).toBeVisible(); await expect(page.locator('video')).toHaveCount(0);
});

test('direct entry has no synthetic Sources trap after a retired Forward entry', async ({page, baseURL}) => {
  await page.goto(new URL('pair.html', baseURL!).href);
  await expect(page.getByRole('status')).toHaveText('Timing core ready');
  // This bounded test-only read is confined to a fresh owned browser context.
  // The Navigation API omits cross-origin predecessors such as about:blank,
  // whereas ordinary browser Back can reach them. Production uses neither API.
  const session = await page.context().newCDPSession(page);
  const nativeHistory = await session.send('Page.getNavigationHistory');
  await session.detach();
  const previous = nativeHistory.entries[nativeHistory.currentIndex - 1];
  const prior = {available: nativeHistory.currentIndex > 0, url: previous?.url};
  console.log(JSON.stringify({evidence: 'owned direct-entry history', previous: prior.available,
    previousKind: prior.url === 'about:blank' ? 'initial-blank' : prior.url ? 'owned-page' : 'none'}));
  await watch(page); await page.locator('#sources').click(); await sources(page);
  await page.goForward(); await sources(page);
  await page.goBack({waitUntil: 'commit'});
  if (prior.available) {
    expect(typeof prior.url).toBe('string');
    await expect(page).toHaveURL(prior.url);
  } else {
    await expect.poll(() => page.evaluate(() => history.state?.__tachiaiFixtureHistory?.position)).toBe(0);
    await expect(page.locator('#watch')).toBeEnabled();
  }
});

test('a genuinely BFCache-restored retired document stays Sources until fresh Watch', async ({baseURL}) => {
  test.setTimeout(60000);
  // Playwright normally disables BFCache. This one owned-browser trial removes
  // that default and requires an actual persisted pageshow, not a fake event.
  const browser = await chromium.launch({channel: 'chrome', ignoreDefaultArgs: ['--disable-back-forward-cache']});
  const context = await browser.newContext();
  await context.addInitScript(() => {
    (window as any).historyDocumentIdentity = crypto.randomUUID();
    window.addEventListener('pageshow', event => {(window as any).historyPersistedShow = event.persisted;});
  });
  const page = await context.newPage();
  page.setDefaultNavigationTimeout(10000);
  try {
    await test.step('play and explicitly retire the owned presentation', async () => {
      await arrive(page, baseURL!); await watch(page);
      await page.locator('#sources').click(); await sources(page);
    });
    const identity = await page.evaluate(() => (window as any).historyDocumentIdentity);
    await test.step('leave to the real prior document', async () => {
      await page.goBack({waitUntil: 'commit'}); await expect(page).toHaveURL(/history-prior=second$/);
    });
    await test.step('Forward restores the same document from genuine BFCache', async () => {
      await page.goForward({waitUntil: 'commit'});
      await page.waitForFunction(() => typeof (window as any).historyPersistedShow === 'boolean');
      const proof = await page.evaluate(identity => {
        const navigation = performance.getEntriesByType('navigation')[0] as any;
        const reasons: string[] = [];
        const visit = (value: any, depth = 0) => {
          if (!value || depth > 4 || reasons.length >= 16) return;
          for (const item of value.reasons ?? []) if (typeof item.reason === 'string' && item.reason.length <= 100) reasons.push(item.reason);
          for (const child of value.children ?? []) visit(child, depth + 1);
        };
        visit(navigation?.notRestoredReasons);
        return {persisted: (window as any).historyPersistedShow === true,
          sameDocument: (window as any).historyDocumentIdentity === identity, reasons};
      }, identity);
      console.log(JSON.stringify({evidence: 'owned BFCache navigation', ...proof}));
      expect(proof.persisted).toBe(true); expect(proof.sameDocument).toBe(true);
    });
    await sources(page); expect(await page.evaluate(() => window.pair.observe().mode)).toBe('sources');
    await page.locator('#watch').click(); await playing(page);
  } finally {await context.close(); await browser.close();}
});
