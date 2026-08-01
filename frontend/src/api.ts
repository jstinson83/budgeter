export interface MeResponse {
  username: string;
}

export interface ErrorResponse {
  error: string;
}

async function parseJson<T>(response: Response): Promise<T> {
  return (await response.json()) as T;
}

export async function fetchCurrentUser(): Promise<MeResponse | null> {
  const response = await fetch("/api/me", { credentials: "same-origin" });
  if (response.status === 401) {
    return null;
  }
  if (!response.ok) {
    throw new Error(`Unexpected response checking session: ${response.status}`);
  }
  return parseJson<MeResponse>(response);
}

export async function login(username: string, password: string): Promise<MeResponse> {
  const response = await fetch("/api/login", {
    method: "POST",
    credentials: "same-origin",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  });

  if (!response.ok) {
    const body = await parseJson<ErrorResponse>(response).catch(() => ({ error: "unknown_error" }));
    throw new Error(body.error ?? "login_failed");
  }

  return parseJson<MeResponse>(response);
}

export async function logout(): Promise<void> {
  await fetch("/api/logout", { method: "POST", credentials: "same-origin" });
}
