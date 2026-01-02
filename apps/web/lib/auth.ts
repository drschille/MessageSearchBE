const TOKEN_KEY = "messagesearch.jwt";

export function getAuthToken(): string | undefined {
  if (typeof window === "undefined") {
    return process.env.NEXT_PUBLIC_DEV_JWT || undefined;
  }

  return window.localStorage.getItem(TOKEN_KEY) ||
    process.env.NEXT_PUBLIC_DEV_JWT ||
    undefined;
}

export function setAuthToken(token: string) {
  if (typeof window === "undefined") {
    return;
  }

  window.localStorage.setItem(TOKEN_KEY, token);
}

export function clearAuthToken() {
  if (typeof window === "undefined") {
    return;
  }

  window.localStorage.removeItem(TOKEN_KEY);
}
