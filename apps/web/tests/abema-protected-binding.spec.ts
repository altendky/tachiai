import { test, expect } from '@playwright/test';

test.beforeEach(async ({ page }) => { await page.goto('./'); });

test('renderer independently binds the exact fresh replay, advertisement, token and license', async ({ page }) => {
  const result = await page.evaluate(async () => {
    const { protectedReplayBinding } = await import('/harness/src/abema-protected-binding.ts');
    const original = 'https://vod-abematv.akamaized.net/program/394-72_s10_p8529/manifest.mpd?quality=auto';
    const packet = { request: { episodeId: '394-72_s10_p8529', nonce: 'a'.repeat(32),
      uri: original + '&t=PUBLIC_SYNTHETIC_TOKEN&enc=clear', advertisement: { uri: original,
        systemId: '81376844-f976-481e-a84e-cc25d39b0b33', adMode: 4 },
      mediaToken: 'PUBLIC_SYNTHETIC_TOKEN', protection: 'CUSTOM', adMode: 'CSAI',
      clockSampleNs: '1000000000', deadlineNs: '121000000000', expiresAtMs: 110000 },
      userId: 'PUBLIC_SYNTHETIC_USER', licenseUri: 'https://license.p-c3-e.abema-tv.com/abematv-dash?t=PUBLIC_SYNTHETIC_TOKEN&ct=program&cid=394-72_s10_p8529', deadlineMs: 10100 };
    const binding = protectedReplayBinding(packet, 100, 100000);
    packet.request.mediaToken = 'CHANGED_AFTER_BINDING';
    return { frozen: Object.isFrozen(binding), originalToken: binding.mediaToken === 'PUBLIC_SYNTHETIC_TOKEN',
      episode: binding.episodeId, deadline: binding.deadlineMs };
  });
  expect(result).toEqual({ frozen: true, originalToken: true, episode: '394-72_s10_p8529', deadline: 10100 });
});

test('renderer refuses stale, substituted, duplicate and unbound source inputs before player construction', async ({ page }) => {
  const results = await page.evaluate(async () => {
    const { protectedReplayBinding } = await import('/harness/src/abema-protected-binding.ts');
    const source = 'https://vod-abematv.akamaized.net/program/394-72_s10_p8529/manifest.mpd';
    const make = () => ({ request: { episodeId: '394-72_s10_p8529', nonce: 'a'.repeat(32),
      uri: source + '?t=PUBLIC_SYNTHETIC_TOKEN&enc=clear', advertisement: { uri: source,
        systemId: '81376844-f976-481e-a84e-cc25d39b0b33', adMode: 4 },
      mediaToken: 'PUBLIC_SYNTHETIC_TOKEN', protection: 'CUSTOM', adMode: 'CSAI',
      clockSampleNs: '1000000000', deadlineNs: '121000000000', expiresAtMs: 110000 },
      userId: 'PUBLIC_SYNTHETIC_USER', licenseUri: 'https://license.p-c3-e.abema-tv.com/abematv-dash?t=PUBLIC_SYNTHETIC_TOKEN&ct=program&cid=394-72_s10_p8529', deadlineMs: 10100 });
    const changes: ((packet: any) => void)[] = [
      p => { p.request.episodeId = 'different'; }, p => { p.request.nonce = ''; },
      p => { p.request.protection = 'CLEAR'; }, p => { p.request.adMode = 'NONE'; },
      p => { p.request.advertisement.systemId = 'foreign'; },
      p => { p.request.mediaToken = 'SUBSTITUTED'; }, p => { p.request.expiresAtMs = 100000; },
      p => { p.deadlineMs = 100; }, p => { p.deadlineMs = 120101; },
      p => { p.request.deadlineNs = '121000000001'; },
      p => { p.request.deadlineNs = '1001000000'; },
      p => { p.request.adMode = 'NONE'; p.request.advertisement.adMode = 0; },
      p => { p.request.uri += '&t=PUBLIC_SYNTHETIC_TOKEN'; },
      p => { p.request.uri += '&undeclared=extra'; },
      p => { p.request.uri = p.request.uri.replace('vod-abematv', 'foreign-abematv'); },
      p => { p.request.advertisement.uri += '?enc=clear'; },
      p => { p.licenseUri += '&cid=394-72_s10_p8529'; },
      p => { p.licenseUri = p.licenseUri.replace('ct=program', 'ct=channel'); },
      p => { p.licenseUri = p.licenseUri.replace('PUBLIC_SYNTHETIC_TOKEN', 'SUBSTITUTED'); },
      p => { p.licenseUri = p.licenseUri.replace('license.p-c3-e.abema-tv.com', 'foreign.invalid'); },
      p => { p.request.extra = true; }, p => { p.extra = true; },
      p => { Object.defineProperty(p.request, 'nonce', { get: () => { throw Error('accessor read'); } }); },
    ];
    return changes.map(change => { const packet = make(); change(packet); try { protectedReplayBinding(packet, 100, 100000); return false; } catch { return true; } });
  });
  expect(results).toEqual(Array(23).fill(true));
});

for (const retirement of ['close', 'pagehide']) {
  test(retirement + ' before preparation permanently retires the proof page', async ({ page }) => {
    const result = await page.evaluate(async kind => {
      document.body.innerHTML = '<button id="play" disabled>Play</button><div id="surface"></div>';
      let captures = 0;
      window.__episodeTakeFetch = () => { captures++; throw Error('Must remain unused'); };
      window.__episodeOpaqueTransform = () => new ArrayBuffer(1);
      await import('/harness/src/abema-protected-playback-entry.ts');
      if (kind === 'close') await window.abemaProtectedProof!.close();
      else window.dispatchEvent(new Event('pagehide'));
      const source = 'https://vod-abematv.akamaized.net/program/394-72_s10_p8529/manifest.mpd';
      const request = { episodeId: '394-72_s10_p8529', nonce: 'a'.repeat(32),
        uri: source + '?t=PUBLIC_SYNTHETIC_TOKEN&enc=clear', advertisement: { uri: source,
          systemId: '81376844-f976-481e-a84e-cc25d39b0b33', adMode: 4 },
        mediaToken: 'PUBLIC_SYNTHETIC_TOKEN', protection: 'CUSTOM', adMode: 'CSAI',
        clockSampleNs: '1000000000', deadlineNs: '121000000000', expiresAtMs: Date.now() + 30000 };
      const prepare = await window.abemaProtectedProof!.prepare({ request, userId: 'PUBLIC_SYNTHETIC_USER',
        licenseUri: 'https://license.p-c3-e.abema-tv.com/abematv-dash?t=PUBLIC_SYNTHETIC_TOKEN&ct=program&cid=394-72_s10_p8529', deadlineMs: performance.now() + 10000 });
      return { captures, prepare, play: await window.abemaProtectedProof!.play(),
        close: await window.abemaProtectedProof!.close(), evidence: window.abemaProtectedProof!.evidence(),
        disabled: (document.getElementById('play') as HTMLButtonElement).disabled };
    }, retirement);
    expect(result).toEqual({ captures: 0, prepare: 'refused', play: 'refused', close: 'confirmed', evidence: null, disabled: true });
  });
}
