if ("serviceWorker" in navigator) {
  window.addEventListener("load", () => {
    navigator.serviceWorker.register("/sw.js").catch(() => {
      // Installability/offline support is a progressive enhancement here -
      // a failed registration (unsupported browser, blocked by extension,
      // dev environment quirk) shouldn't affect the app itself.
    });
  });
}
