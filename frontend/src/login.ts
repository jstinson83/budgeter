import "./style.css";
import { fetchCurrentUser, login } from "./api";

const form = document.querySelector<HTMLFormElement>("#login-form")!;
const usernameInput = document.querySelector<HTMLInputElement>("#username")!;
const passwordInput = document.querySelector<HTMLInputElement>("#password")!;
const errorEl = document.querySelector<HTMLParagraphElement>("#error")!;
const submitButton = document.querySelector<HTMLButtonElement>("#submit")!;

async function redirectIfAlreadySignedIn() {
  const user = await fetchCurrentUser().catch(() => null);
  if (user) {
    window.location.href = "/";
  }
}

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  errorEl.textContent = "";
  submitButton.disabled = true;

  try {
    await login(usernameInput.value, passwordInput.value);
    window.location.href = "/";
  } catch (err) {
    errorEl.textContent = "Invalid username or password.";
    submitButton.disabled = false;
  }
});

void redirectIfAlreadySignedIn();
