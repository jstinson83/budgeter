import "./style.css";
import { fetchCurrentUser, logout } from "./api";

const app = document.querySelector<HTMLDivElement>("#app")!;
const usernameEl = document.querySelector<HTMLParagraphElement>("#username")!;
const logoutButton = document.querySelector<HTMLButtonElement>("#logout")!;

async function requireSession() {
  const user = await fetchCurrentUser().catch(() => null);

  if (!user) {
    window.location.href = "/login.html";
    return;
  }

  usernameEl.textContent = `Signed in as ${user.username}`;
  app.hidden = false;
}

logoutButton.addEventListener("click", async () => {
  await logout();
  window.location.href = "/login.html";
});

void requireSession();
