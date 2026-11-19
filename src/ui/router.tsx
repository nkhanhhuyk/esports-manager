/**
 * Minimal hash router: browser back/forward work natively via the History API, links are plain
 * anchors (so middle-click / long-press work), and routes are parsed into {page, id, query}.
 */
import { useEffect, useState, type ReactNode, type MouseEvent } from 'react';

export interface Route {
  page: string;
  id?: string;
  query: Record<string, string>;
  path: string;
}

export function parseHash(hash: string): Route {
  const h = hash.replace(/^#/, '') || '/';
  const [pathPart, qs] = h.split('?');
  const parts = pathPart.split('/').filter(Boolean);
  const query: Record<string, string> = {};
  if (qs) for (const kv of qs.split('&')) {
    const [k, v] = kv.split('=');
    if (k) query[decodeURIComponent(k)] = decodeURIComponent(v ?? '');
  }
  return { page: parts[0] ?? 'home', id: parts[1] ? decodeURIComponent(parts[1]) : undefined, query, path: h };
}

export function useRoute(): Route {
  const [route, setRoute] = useState(() => parseHash(window.location.hash));
  useEffect(() => {
    const on = () => setRoute(parseHash(window.location.hash));
    window.addEventListener('hashchange', on);
    return () => window.removeEventListener('hashchange', on);
  }, []);
  return route;
}

export function navigate(path: string): void {
  const target = path.startsWith('#') ? path : `#${path}`;
  if (window.location.hash !== target) window.location.hash = target;
}

export function href(path: string): string {
  return path.startsWith('#') ? path : `#${path}`;
}

export function Link({ to, children, className, title }: { to: string; children: ReactNode; className?: string; title?: string }) {
  return (
    <a
      href={href(to)}
      className={className}
      title={title}
      onClick={(e: MouseEvent) => {
        e.stopPropagation();
      }}
    >
      {children}
    </a>
  );
}

export function withQuery(route: Route, patch: Record<string, string | undefined>): string {
  const q = { ...route.query, ...patch };
  const qs = Object.entries(q)
    .filter(([, v]) => v !== undefined && v !== '')
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v!)}`)
    .join('&');
  const base = `/${route.page}${route.id ? `/${encodeURIComponent(route.id)}` : ''}`;
  return qs ? `${base}?${qs}` : base;
}
