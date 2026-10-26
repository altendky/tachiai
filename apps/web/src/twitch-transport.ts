// Actual browser Fetch boundary. Playwright routing can distort CORS behavior.
export function createClosedBrowserFetch(
  admit: (url: URL, method: string) => boolean,
): typeof fetch {
  return async (input, options = {}) => {
    if (typeof input !== 'string' && !(input instanceof URL)) throw new Error('Request refused');
    const url = new URL(String(input));
    const method = options.method ?? 'GET';
    if (url.username || url.password || url.hash || !admit(url, method)) throw new Error('Request refused');
    return globalThis.fetch(url.href, {
      ...options, mode: 'cors', credentials: 'omit', redirect: 'error', cache: 'no-store',
    });
  };
}

export function admittedTwitchApi(url: URL, method: string): boolean {
  if (url.search || url.port || url.username || url.password || url.hash) return false;
  if (url.origin === 'https://id.twitch.tv') {
    return method === 'POST' && ['/oauth2/device', '/oauth2/token', '/oauth2/revoke'].includes(url.pathname) ||
      method === 'GET' && url.pathname === '/oauth2/validate';
  }
  return method === 'POST' && url.href === 'https://gql.twitch.tv/gql';
}
